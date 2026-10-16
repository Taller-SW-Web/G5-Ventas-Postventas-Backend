package ventas.pedido;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import ventas.anulacion.AnulacionSistemaService;
import ventas.anulacion.ReembolsoAnulacionService;
import ventas.compartido.CodigoPublico;
import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.envios.NotificadorEnvios;
import ventas.integracion.eventos.EventoRecibido;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.dto.EventoDespachoRequest;
import ventas.pedido.dto.EventoDespachoResponse;

/**
 * Webhook de Despacho (api-ventas 1.5, acuerdo G2 D2 a D6). Reemplaza la vieja 1.5 (PATCH estado) y 1.6.
 * | tipo                       | efecto                                                                  |
 * |----------------------------|-------------------------------------------------------------------------|
 * | DESPACHO_EN_CAMINO         | EN_PREPARACION -> DESPACHADO                                            |
 * | DESPACHO_ENTREGADO         | DESPACHADO -> ENTREGADO                                                 |
 * | DESPACHO_DEVUELTO_A_ORIGEN | DESPACHADO -> ANULADO (ENTREGA_FALLIDA_DEFINITIVA) + reembolso a M2     |
 * | DESPACHO_REPROGRAMADO      | pedido.fecha_estimada_entrega = datos.nuevaFechaProgramada              |
 * | ASIGNADO, FALLIDO, CANCELADO | solo se registra (auditoría / acuse de una cancelación nuestra)       |
 * EN_CAMINO, ENTREGADO y REPROGRAMADO además avisan a Marketplace (1.10, acuerdo H-05) si el pedido es de ese canal.
 * Las órdenes de reemplazo (F3) no se avisan a Marketplace: M2 sigue el cambio con pedido.entregado / pedido.anulado,
 * y si vuelven a origen no hay reembolso (total 0).
 * Si el pedido ya está en el estado al que llevaría el evento (llegó repetido con otro idEvento o en desorden),
 * no se hace nada. Idempotencia por idEvento en evento_recibido (origen SISTEMA_LOGISTICA).
 * Actor siempre SISTEMA_LOGISTICA (ESP-03).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventoDespachoService {

    static final String ENTREGA_FALLIDA = "ENTREGA_FALLIDA_DEFINITIVA";
    /** Prefijo en evento_recibido.id_evento, para no chocar con ids de la pasarela o de Productos. */
    static final String PREFIJO = "G2:";

    private final PedidoRepository pedidos;
    private final EventoRecibidoRepository recibidos;
    private final MaquinaEstados maquina;
    private final AnulacionSistemaService anulaciones;
    private final ReembolsoAnulacionService reembolsos;
    private final NotificadorEnvios envios;
    private final TransactionOperations transaccion;

    /** anulacionId != null: después del commit hay que mandar el reembolso a M2. */
    private record Hecho(EventoDespachoResponse respuesta, String anulacionId) {}

    public EventoDespachoResponse procesar(EventoDespachoRequest ev) {
        Hecho hecho;
        try {
            hecho = transaccion.execute(estado -> aplicar(ev));
        } catch (DataIntegrityViolationException e) {
            // El mismo evento llegó dos veces a la vez y el otro ganó
            hecho = transaccion.execute(estado -> new Hecho(repetido(ev, buscar(ev.idPedido())), null));
        }
        if (hecho.anulacionId() != null) {
            reembolsos.enviar(hecho.anulacionId());   // si M2 falla, queda PENDIENTE y se reintenta solo
        }
        return hecho.respuesta();
    }

    private Hecho aplicar(EventoDespachoRequest ev) {
        Pedido p = buscar(ev.idPedido());
        String idEvento = PREFIJO + ev.idEvento();
        if (recibidos.existsByIdEventoAndOrigen(idEvento, OrigenEvento.SISTEMA_LOGISTICA)) {
            return new Hecho(repetido(ev, p), null);
        }
        recibidos.saveAndFlush(new EventoRecibido(idEvento, OrigenEvento.SISTEMA_LOGISTICA, OffsetDateTime.now(ZoneOffset.UTC)));
        OffsetDateTime cuando = ev.ocurridoEn() != null ? ev.ocurridoEn() : OffsetDateTime.now(ZoneOffset.UTC);
        String anulacionId = null;
        String mensaje = switch (ev.tipo()) {
            case DESPACHO_EN_CAMINO -> avanzar(p, EstadoPedido.EN_PREPARACION, EstadoPedido.DESPACHADO, cuando, ev);
            case DESPACHO_ENTREGADO -> avanzar(p, EstadoPedido.DESPACHADO, EstadoPedido.ENTREGADO, cuando, ev);
            case DESPACHO_DEVUELTO_A_ORIGEN -> {
                if (p.getEstado() == EstadoPedido.ANULADO) {
                    yield "El pedido ya estaba ANULADO";
                }
                exigir(p, EstadoPedido.DESPACHADO, ev.tipo());
                anulacionId = anulaciones.registrarEjecutada(p.getId(), ENTREGA_FALLIDA);
                reembolsos.registrar(anulacionId, p.getId(), p.getTotal(), p.getMoneda().trim(), motivoReembolso(ev));
                maquina.cambiar(p, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_LOGISTICA, ENTREGA_FALLIDA, cuando);
                yield p.esReemplazo() ? "Reemplazo anulado; M2 reabre la devolución (sin reembolso, no se cobró)"
                        : "Pedido anulado e instrucción de reembolso generada hacia M2 con origen ANULACION";
            }
            case DESPACHO_REPROGRAMADO -> {
                p.setFechaEstimadaEntrega(nuevaFecha(ev.datos()));
                pedidos.save(p);
                if (!p.esReemplazo()) {
                    envios.registrar(p, ev.idDespacho());   // 1.10: Marketplace muestra la nueva fecha
                }
                yield "Fecha estimada de entrega actualizada";
            }
            case DESPACHO_ASIGNADO, DESPACHO_FALLIDO, DESPACHO_CANCELADO -> "Evento registrado; no cambia el estado del pedido";
        };
        log.info("Evento {} {} de {}: {}", ev.tipo(), ev.idEvento(), p.getCodigo(), mensaje);
        return new Hecho(new EventoDespachoResponse(ev.idEvento(), p.getCodigo(), p.getEstado(), false, mensaje), anulacionId);
    }

    /** Si ya está en el destino (evento duplicado con otro idEvento), no hace nada. */
    private String avanzar(Pedido p, EstadoPedido origen, EstadoPedido destino, OffsetDateTime cuando,
                           EventoDespachoRequest ev) {
        if (p.getEstado() == destino) {
            return "El pedido ya estaba " + destino;
        }
        exigir(p, origen, null);
        maquina.cambiar(p, destino, ActorHistorial.SISTEMA_LOGISTICA, null, cuando);
        if (!p.esReemplazo()) {
            envios.registrar(p, ev.idDespacho());   // 1.10: aviso público a Marketplace (DESPACHADO / ENTREGADO)
        }
        return "Pedido " + destino;
    }

    private static void exigir(Pedido p, EstadoPedido esperado, TipoEventoDespacho tipo) {
        if (p.getEstado() != esperado) {
            throw new ConflictoException("TRANSICION_NO_PERMITIDA",
                    "El pedido %s está en %s; se esperaba %s".formatted(p.getCodigo(), p.getEstado(), esperado));
        }
    }

    private EventoDespachoResponse repetido(EventoDespachoRequest ev, Pedido p) {
        return new EventoDespachoResponse(ev.idEvento(), p.getCodigo(), p.getEstado(), true, "Evento ya procesado");
    }

    /** Acepta fecha con hora (2026-10-14T15:00:00Z) o solo fecha (2026-10-14, se toma a las 00:00 UTC). */
    static OffsetDateTime nuevaFecha(Map<String, Object> datos) {
        Object valor = datos == null ? null : datos.get("nuevaFechaProgramada");
        if (valor == null || valor.toString().isBlank()) {
            throw new SolicitudInvalidaException("DATOS_INVALIDOS", "DESPACHO_REPROGRAMADO exige datos.nuevaFechaProgramada");
        }
        String texto = valor.toString();
        try {
            return OffsetDateTime.parse(texto);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(texto).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                throw new SolicitudInvalidaException("DATOS_INVALIDOS", "nuevaFechaProgramada no es una fecha ISO: " + texto);
            }
        }
    }

    private static String motivoReembolso(EventoDespachoRequest ev) {
        Object cierre = ev.datos() == null ? null : ev.datos().get("motivoCierre");
        return "Devuelto a origen por Despacho" + (cierre != null ? " (" + cierre + ")" : "")
                + (ev.idDespacho() != null ? ", " + ev.idDespacho() : "");
    }

    private Pedido buscar(String pedidoId) {
        long id = CodigoPublico.id(pedidoId);
        return pedidos.findById(id)
                .filter(p -> p.getCodigo().equals(pedidoId))
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + pedidoId));
    }
}

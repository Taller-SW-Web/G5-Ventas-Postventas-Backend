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
 * Despacho reintenta todo lo que no sea 2xx (5 reintentos en ~16 min, luego ENVIO_FALLIDO), así que el webhook
 * SIEMPRE responde 2xx salvo token o cuerpo inválidos (acuerdo G2, 18/11):
 * - evento repetido (mismo idEvento): 200, sin cambios (idempotencia en evento_recibido, origen SISTEMA_LOGISTICA);
 * - evento atrasado (el pedido ya está igual o más adelante, p. ej. EN_CAMINO tras ENTREGADO o tras un FALLIDO): 200,
 *   no se retrocede el estado;
 * - ENTREGADO antes que EN_CAMINO (desorden por reintentos): se aplican los dos pasos (DESPACHADO y ENTREGADO);
 * - pedido desconocido, estado que no admite el evento o datos inválidos: 200 "ignorado" con log WARN para revisar.
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
        if (pedidos.findById(idOMenosUno(ev.idPedido())).filter(p -> p.getCodigo().equals(ev.idPedido())).isEmpty()) {
            // 2xx para que Despacho no lo reintente 6 veces; queda en el log para revisarlo (pregunta N4 de G2)
            log.warn("Evento {} {} de Despacho para un pedido desconocido ({}): ignorado", ev.tipo(), ev.idEvento(), ev.idPedido());
            return new EventoDespachoResponse(ev.idEvento(), ev.idPedido(), null, false, "Pedido no reconocido; evento ignorado");
        }
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
            case DESPACHO_EN_CAMINO -> avanzar(p, EstadoPedido.DESPACHADO, cuando, ev);
            case DESPACHO_ENTREGADO -> avanzar(p, EstadoPedido.ENTREGADO, cuando, ev);
            case DESPACHO_DEVUELTO_A_ORIGEN -> {
                if (p.getEstado() == EstadoPedido.ANULADO) {
                    yield "El pedido ya estaba ANULADO";
                }
                if (p.getEstado() != EstadoPedido.DESPACHADO && p.getEstado() != EstadoPedido.EN_PREPARACION) {
                    yield ignorado(p, ev);   // p. ej. ENTREGADO: no se anula un pedido entregado
                }
                anulacionId = anulaciones.registrarEjecutada(p.getId(), ENTREGA_FALLIDA);
                reembolsos.registrar(anulacionId, p.getId(), p.getTotal(), p.getMoneda().trim(), motivoReembolso(ev));
                maquina.cambiar(p, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_LOGISTICA, ENTREGA_FALLIDA, cuando);
                yield p.esReemplazo() ? "Reemplazo anulado; M2 reabre la devolución (sin reembolso, no se cobró)"
                        : "Pedido anulado e instrucción de reembolso generada hacia M2 con origen ANULACION";
            }
            case DESPACHO_REPROGRAMADO -> {
                OffsetDateTime fecha;
                try {
                    fecha = nuevaFecha(ev.datos());
                } catch (SolicitudInvalidaException e) {
                    log.warn("Evento {} {} de {} con datos inválidos: {}", ev.tipo(), ev.idEvento(), p.getCodigo(), e.getMessage());
                    yield "Datos inválidos; evento registrado sin cambios (" + e.getMessage() + ")";
                }
                p.setFechaEstimadaEntrega(fecha);
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

    /**
     * Avanza EN_PREPARACION -> DESPACHADO -> ENTREGADO sin retroceder nunca. Si el pedido ya está en el destino o más
     * adelante (evento atrasado), no hace nada. ENTREGADO sobre EN_PREPARACION aplica los dos pasos.
     */
    private String avanzar(Pedido p, EstadoPedido destino, OffsetDateTime cuando, EventoDespachoRequest ev) {
        EstadoPedido actual = p.getEstado();
        if (actual == destino) {
            return "El pedido ya estaba " + destino;
        }
        if (actual == EstadoPedido.ENTREGADO || actual == EstadoPedido.ANULADO
                || (actual == EstadoPedido.DESPACHADO && destino == EstadoPedido.DESPACHADO)) {
            return "Evento atrasado: el pedido ya está " + actual + "; no se retrocede";
        }
        if (actual != EstadoPedido.EN_PREPARACION && actual != EstadoPedido.DESPACHADO) {
            return ignorado(p, ev);
        }
        if (actual == EstadoPedido.EN_PREPARACION && destino == EstadoPedido.ENTREGADO) {
            cambiar(p, EstadoPedido.DESPACHADO, cuando, ev);   // EN_CAMINO llegó tarde o se perdió
        }
        cambiar(p, destino, cuando, ev);
        return "Pedido " + destino;
    }

    private void cambiar(Pedido p, EstadoPedido destino, OffsetDateTime cuando, EventoDespachoRequest ev) {
        maquina.cambiar(p, destino, ActorHistorial.SISTEMA_LOGISTICA, null, cuando);
        if (!p.esReemplazo()) {
            envios.registrar(p, ev.idDespacho());   // 1.10: aviso público a Marketplace (DESPACHADO / ENTREGADO)
        }
    }

    private static String ignorado(Pedido p, EventoDespachoRequest ev) {
        log.warn("Evento {} {} de Despacho no aplica a {} en estado {}: ignorado", ev.tipo(), ev.idEvento(),
                p.getCodigo(), p.getEstado());
        return "El pedido está " + p.getEstado() + "; evento registrado sin cambios";
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

    /** Id interno del código público; -1 si el código no tiene el formato esperado. */
    private static long idOMenosUno(String pedidoId) {
        try {
            return CodigoPublico.id(pedidoId);
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    private Pedido buscar(String pedidoId) {
        long id = CodigoPublico.id(pedidoId);
        return pedidos.findById(id)
                .filter(p -> p.getCodigo().equals(pedidoId))
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + pedidoId));
    }
}

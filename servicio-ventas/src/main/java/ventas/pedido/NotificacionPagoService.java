package ventas.pedido;

import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import ventas.compartido.CodigoPublico;
import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.eventos.EventoRecibido;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.dto.NotificacionPagoRequest;
import ventas.pedido.dto.NotificacionPagoResponse;

/**
 * 1.2 Notificación de pago (ESP-03 7.3). APROBADO: CREADO -> PAGADO.
 * RECHAZADO: CREADO -> ANULADO con motivo PAGO_NO_COMPLETADO, sin Gestor.
 * El transaccionId es la clave de idempotencia: se guarda en evento_recibido y en pago.referencia.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificacionPagoService {

    static final String MOTIVO_PAGO_OK = "Confirmación de cobro exitoso";
    static final String MOTIVO_PAGO_RECHAZADO = "PAGO_NO_COMPLETADO";

    private final PedidoRepository pedidos;
    private final PagoRepository pagos;
    private final EventoRecibidoRepository recibidos;
    private final MaquinaEstados maquina;
    private final TransactionOperations transaccion;

    public NotificacionPagoResponse procesar(String pedidoId, NotificacionPagoRequest req) {
        try {
            return transaccion.execute(estado -> aplicar(pedidoId, req));
        } catch (DataIntegrityViolationException e) {
            // La misma notificación llegó dos veces a la vez y la otra ganó: se responde como reintento
            return transaccion.execute(estado -> reintento(buscar(pedidoId), req));
        }
    }

    private NotificacionPagoResponse aplicar(String pedidoId, NotificacionPagoRequest req) {
        Pedido pedido = buscar(pedidoId);

        if (recibidos.existsByIdEventoAndOrigen(req.transaccionId(), OrigenEvento.PASARELA_PAGOS)) {
            return reintento(pedido, req);
        }
        if (pedido.getEstado() != EstadoPedido.CREADO) {
            // ESP-03 7.4 C: notificación tardía, se rechaza y queda en el log para revisión manual
            log.warn("Notificación {} {} sobre {} en estado {}: rechazada",
                    req.transaccionId(), req.resultado(), pedido.getCodigo(), pedido.getEstado());
            throw new ConflictoException("ESTADO_NO_PERMITE_PAGO",
                    "El pedido %s no está en estado CREADO (está en %s)".formatted(pedido.getCodigo(), pedido.getEstado()));
        }
        validarMonto(pedido, req);

        // Primero la clave: si otra petición igual ya la insertó, falla aquí y no se cambia nada
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        recibidos.saveAndFlush(new EventoRecibido(req.transaccionId(), OrigenEvento.PASARELA_PAGOS, ahora));

        OffsetDateTime fecha = req.fechaPago() != null ? req.fechaPago() : ahora;
        Pago pago = pagos.findByPedidoId(pedido.getId()).orElseGet(() -> nuevoPago(pedido));
        pago.setEstado(req.resultado().aEstadoPago());
        pago.setReferencia(req.transaccionId());
        pago.setFechaProceso(fecha);
        if (req.metodo() != null && !req.metodo().isBlank()) {
            pago.setMetodo(req.metodo());
        }
        pagos.save(pago);

        if (req.resultado() == ResultadoPasarela.APROBADO) {
            maquina.cambiar(pedido, EstadoPedido.PAGADO, ActorHistorial.PASARELA_PAGOS, MOTIVO_PAGO_OK, fecha);
        } else {
            maquina.cambiar(pedido, EstadoPedido.ANULADO, actorRechazo(pedido), MOTIVO_PAGO_RECHAZADO, fecha);
        }
        return new NotificacionPagoResponse(pedido.getCodigo(), pedido.getEstado(), req.transaccionId(), fecha);
    }

    /** Misma transacción ya procesada: no se toca nada y se responde con el estado actual. */
    private NotificacionPagoResponse reintento(Pedido pedido, NotificacionPagoRequest req) {
        Pago pago = pagos.findByPedidoId(pedido.getId())
                .filter(p -> req.transaccionId().equals(p.getReferencia()))
                .orElseThrow(() -> new ConflictoException("TRANSACCION_DE_OTRO_PEDIDO",
                        "La transacción %s ya se registró en otro pedido".formatted(req.transaccionId())));
        return new NotificacionPagoResponse(pedido.getCodigo(), pedido.getEstado(), req.transaccionId(),
                pago.getFechaProceso());
    }

    private Pedido buscar(String pedidoId) {
        long id = CodigoPublico.id(pedidoId);
        return pedidos.findById(id)
                .filter(p -> p.getCodigo().equals(pedidoId))
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + pedidoId));
    }

    private static void validarMonto(Pedido pedido, NotificacionPagoRequest req) {
        boolean mismoMonto = req.monto().setScale(2, RoundingMode.HALF_UP)
                .compareTo(pedido.getTotal().setScale(2, RoundingMode.HALF_UP)) == 0;
        if (!mismoMonto || !req.moneda().equals(pedido.getMoneda().trim())) {
            throw new SolicitudInvalidaException("MONTOS_INCONSISTENTES",
                    "El monto o la moneda no coinciden con el pedido (%s %s)".formatted(pedido.getTotal(), pedido.getMoneda().trim()));
        }
    }

    /** ESP-03 nombra CANAL_CHATBOT; para Marketplace el aviso viene de la pasarela (la matriz permite ambos). */
    private static ActorHistorial actorRechazo(Pedido pedido) {
        return pedido.getCanal() == CanalVenta.CHATBOT ? ActorHistorial.CANAL_CHATBOT : ActorHistorial.PASARELA_PAGOS;
    }

    private static Pago nuevoPago(Pedido pedido) {
        Pago p = new Pago();
        p.setPedido(pedido);
        p.setMonto(pedido.getTotal());
        p.setMetodo("DESCONOCIDO");
        return p;
    }
}

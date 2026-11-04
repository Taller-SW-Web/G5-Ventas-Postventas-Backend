package ventas.pedido;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;

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
import ventas.integracion.ProductosClient;
import ventas.integracion.ProductosClient.MotivoLiberacion;
import ventas.integracion.ProductosClient.ResultadoConfirmacion;
import ventas.integracion.eventos.EventoRecibido;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.dto.NotificacionPagoRequest;
import ventas.pedido.dto.NotificacionPagoResponse;

/**
 * 1.2 Notificación de pago (ESP-03 7.3). APROBADO: CREADO -> PAGADO.
 * RECHAZADO: CREADO -> ANULADO con motivo PAGO_NO_COMPLETADO, sin Gestor. En ambos casos el actor
 * es PASARELA_PAGOS (ESP-03 7.2 y api-ventas 1.2), también cuando la notificación la envía el chatbot.
 * El transaccionId es la clave de idempotencia: se guarda en evento_recibido y en pago.referencia.
 * <p>
 * Cobro confirmado de un pedido ya ANULADO (ESP-03 7.4 C, decisión 2026-11-04): la pasarela sí cobró, así que el
 * pedido sigue ANULADO, el pago queda CONFIRMADO y en la misma transacción se guarda la solicitud de reembolso
 * (ventas.solicitud_reembolso) colgada de la anulación del pedido. Se responde 200 apenas eso queda guardado; el
 * envío a M2 va después del commit y, si M2 no responde, lo recupera ReintentoReembolsosScheduler.
 * No hay transición, ni evento pedido.pagado, ni stock, ni Despacho: el pedido no se vendió.
 * <p>
 * El pedido se lee con SELECT ... FOR UPDATE: una anulación (1.7) o un mensaje de Productos que llegue a la vez espera
 * a que esta transacción termine (y al revés), así pago y anulación concurrentes dejan un solo reembolso.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificacionPagoService {

    static final String MOTIVO_PAGO_OK = "Confirmación de cobro exitoso";
    static final String MOTIVO_PAGO_RECHAZADO = "PAGO_NO_COMPLETADO";
    /** Prefijo del motivo de la solicitud de reembolso de un cobro que llegó con el pedido ya anulado. */
    static final String MOTIVO_COBRO_TRAS_ANULACION = "COBRO_TRAS_ANULACION";

    private final PedidoRepository pedidos;
    private final PagoRepository pagos;
    private final EventoRecibidoRepository recibidos;
    private final MaquinaEstados maquina;
    private final ProductosClient productos;
    private final MensajesProductosService mensajesProductos;
    private final HistorialEstadoRepository historial;
    private final AnulacionSistemaService anulaciones;
    private final ReembolsoAnulacionService reembolsos;
    private final TransactionOperations transaccion;

    /**
     * Resultado de la transacción. reservaId != null: hay que confirmar o liberar el stock después del commit.
     * anulacionAReembolsar != null: hay una solicitud de reembolso nueva que mandar a M2 después del commit.
     */
    private record Aplicado(NotificacionPagoResponse respuesta, String reservaId, ResultadoPasarela resultado,
                            String anulacionAReembolsar) {

        static Aplicado sinEfectos(NotificacionPagoResponse respuesta) {
            return new Aplicado(respuesta, null, null, null);
        }
    }

    public NotificacionPagoResponse procesar(String pedidoId, NotificacionPagoRequest req) {
        Aplicado hecho;
        try {
            hecho = transaccion.execute(estado -> aplicar(pedidoId, req));
        } catch (DataIntegrityViolationException e) {
            // La misma notificación llegó dos veces a la vez y la otra ganó: se responde como reintento
            return transaccion.execute(estado -> reintento(buscar(pedidoId), req));
        }
        String pedido = hecho.respuesta().pedidoId();
        if (hecho.anulacionAReembolsar() != null) {
            enviarReembolso(pedido, hecho.anulacionAReembolsar());
        }
        // Acuerdo G6 y F1 CA-07b: pagado -> se consume la reserva; pago rechazado -> se libera. Fuera de la transacción
        if (hecho.reservaId() != null) {
            if (hecho.resultado() == ResultadoPasarela.APROBADO) {
                if (productos.confirmar(pedido, hecho.reservaId()) == ResultadoConfirmacion.RESERVA_EXPIRADA) {
                    // La reserva venció antes del pago: se anula y se reembolsa (decisión 1, RESERVA_EXPIRADA)
                    mensajesProductos.reservaExpiradaTrasPago(pedido);
                }
            } else {
                productos.liberar(pedido, hecho.reservaId(), MotivoLiberacion.PAGO_NO_COMPLETADO);
            }
        }
        // TODO cupón: restaurarlo aquí cuando exista su consumo por RabbitMQ (kit G6 §18: reserva confirmada ->
        // consumo del cupón -> pago). Hoy no se consume, así que no hay nada que restaurar
        return hecho.respuesta();
    }

    private Aplicado aplicar(String pedidoId, NotificacionPagoRequest req) {
        Pedido pedido = bloquear(pedidoId);

        if (recibidos.existsByIdEventoAndOrigen(req.transaccionId(), OrigenEvento.PASARELA_PAGOS)) {
            return Aplicado.sinEfectos(reintento(pedido, req));
        }
        if (pedido.esReemplazo()) {
            throw new ConflictoException("ESTADO_NO_PERMITE_PAGO",
                    "El pedido %s es una orden de reemplazo (F3): no se cobra".formatted(pedido.getCodigo()));
        }
        if (pedido.getEstado() == EstadoPedido.ANULADO && req.resultado() == ResultadoPasarela.APROBADO) {
            return cobroTrasAnulacion(pedido, req);
        }
        if (pedido.getEstado() != EstadoPedido.CREADO) {
            // ESP-03 7.4 C: un rechazo tardío, o un aviso sobre un pedido que ya avanzó, no cambia nada
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
        registrarResultado(pago, req, fecha);

        if (req.resultado() == ResultadoPasarela.APROBADO) {
            maquina.cambiar(pedido, EstadoPedido.PAGADO, ActorHistorial.PASARELA_PAGOS, MOTIVO_PAGO_OK, fecha);
        } else {
            maquina.cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.PASARELA_PAGOS, MOTIVO_PAGO_RECHAZADO, fecha);
        }
        // Si la reserva todavía no llegó (reservaId null), MensajesProductosService confirma o libera al recibirla
        return new Aplicado(new NotificacionPagoResponse(pedido.getCodigo(), pedido.getEstado(), req.transaccionId(), fecha),
                pedido.getReservaId(), req.resultado(), null);
    }

    /**
     * La pasarela confirmó un cobro de un pedido que ya está ANULADO. Se guardan juntos la clave de idempotencia,
     * el pago CONFIRMADO y la solicitud de reembolso. Si el pedido ya tenía un cobro confirmado con otra transacción,
     * no se sobrescribe: 409 y no se guarda nada (la transacción entera se deshace).
     */
    private Aplicado cobroTrasAnulacion(Pedido pedido, NotificacionPagoRequest req) {
        validarMonto(pedido, req);
        Pago pago = pagos.findByPedidoId(pedido.getId()).orElseGet(() -> nuevoPago(pedido));
        if (pago.getEstado() == EstadoPago.CONFIRMADO) {
            log.error("Cobro {} sobre {} ANULADO, que ya tenía confirmado el cobro {}: no se registra; revisar a mano",
                    req.transaccionId(), pedido.getCodigo(), pago.getReferencia());
            throw new ConflictoException("PAGO_YA_CONFIRMADO",
                    "El pedido %s ya tiene un cobro confirmado con otra transacción".formatted(pedido.getCodigo()));
        }

        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        recibidos.saveAndFlush(new EventoRecibido(req.transaccionId(), OrigenEvento.PASARELA_PAGOS, ahora));
        OffsetDateTime fecha = req.fechaPago() != null ? req.fechaPago() : ahora;
        registrarResultado(pago, req, fecha);

        HistorialEstado anulacion = historial.findByPedidoIdOrderByFechaHoraAsc(pedido.getId()).stream()
                .filter(h -> h.getEstadoNuevo() == EstadoPedido.ANULADO)
                .max(Comparator.comparing(HistorialEstado::getFechaHora))
                .orElseThrow(() -> new IllegalStateException("El pedido " + pedido.getCodigo()
                        + " está ANULADO sin historial de anulación"));
        String motivoAnulacion = anulacion.getMotivo();
        LocalDate fechaAnulacion = anulacion.getFechaHora().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        String anulacionId = anulaciones.asegurarEjecutada(pedido.getId(), motivoAnulacion, fechaAnulacion);
        reembolsos.registrar(anulacionId, pedido.getId(), pedido.getTotal(), pedido.getMoneda().trim(),
                "%s: la pasarela confirmó la transacción %s después de anular el pedido (%s)"
                        .formatted(MOTIVO_COBRO_TRAS_ANULACION, req.transaccionId(), motivoAnulacion));
        log.warn("Cobro {} confirmado sobre {} ya ANULADO ({}): pago registrado y reembolso pendiente con {}",
                req.transaccionId(), pedido.getCodigo(), motivoAnulacion, anulacionId);

        return new Aplicado(new NotificacionPagoResponse(pedido.getCodigo(), pedido.getEstado(), req.transaccionId(), fecha),
                null, req.resultado(), anulacionId);
    }

    /** La responsabilidad ya quedó guardada: si M2 falla, la solicitud sigue PENDIENTE y la reintenta el scheduler. */
    private void enviarReembolso(String pedidoId, String anulacionId) {
        try {
            reembolsos.enviar(anulacionId);
        } catch (RuntimeException e) {
            log.error("No se pudo enviar a M2 el reembolso de {} ({}); queda PENDIENTE para el reintento: {}",
                    pedidoId, anulacionId, e.getMessage());
        }
    }

    /**
     * Misma transacción ya procesada: no se toca nada y se responde con el estado actual. Solo es un reintento si
     * coincide con lo que se guardó (pedido, resultado, monto y moneda); si no, 409 y no se cambia nada.
     */
    private NotificacionPagoResponse reintento(Pedido pedido, NotificacionPagoRequest req) {
        Pago pago = pagos.findByPedidoId(pedido.getId())
                .filter(p -> req.transaccionId().equals(p.getReferencia()))
                .orElseThrow(() -> new ConflictoException("TRANSACCION_DE_OTRO_PEDIDO",
                        "La transacción %s ya se procesó y no es el pago vigente de este pedido".formatted(req.transaccionId())));
        boolean mismoResultado = pago.getEstado() == req.resultado().aEstadoPago();
        if (!mismoResultado || !mismoMonto(pedido, req)) {
            throw new ConflictoException("NOTIFICACION_INCOMPATIBLE",
                    "La transacción %s ya se registró con otro resultado, monto o moneda".formatted(req.transaccionId()));
        }
        return new NotificacionPagoResponse(pedido.getCodigo(), pedido.getEstado(), req.transaccionId(),
                pago.getFechaProceso());
    }

    private void registrarResultado(Pago pago, NotificacionPagoRequest req, OffsetDateTime fecha) {
        pago.setEstado(req.resultado().aEstadoPago());
        pago.setReferencia(req.transaccionId());
        pago.setFechaProceso(fecha);
        if (req.metodo() != null && !req.metodo().isBlank()) {
            pago.setMetodo(req.metodo());
        }
        pagos.save(pago);
    }

    /** Con bloqueo: lo usa la transacción que aplica la notificación. */
    private Pedido bloquear(String pedidoId) {
        long id = CodigoPublico.id(pedidoId);
        return pedidos.bloquear(id)
                .filter(p -> p.getCodigo().equals(pedidoId))
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + pedidoId));
    }

    private Pedido buscar(String pedidoId) {
        long id = CodigoPublico.id(pedidoId);
        return pedidos.findById(id)
                .filter(p -> p.getCodigo().equals(pedidoId))
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + pedidoId));
    }

    private static boolean mismoMonto(Pedido pedido, NotificacionPagoRequest req) {
        return req.monto().setScale(2, RoundingMode.HALF_UP)
                .compareTo(pedido.getTotal().setScale(2, RoundingMode.HALF_UP)) == 0
                && req.moneda().equals(pedido.getMoneda().trim());
    }

    private static void validarMonto(Pedido pedido, NotificacionPagoRequest req) {
        if (!mismoMonto(pedido, req)) {
            throw new SolicitudInvalidaException("MONTOS_INCONSISTENTES",
                    "El monto o la moneda no coinciden con el pedido (%s %s)".formatted(pedido.getTotal(), pedido.getMoneda().trim()));
        }
    }

    private static Pago nuevoPago(Pedido pedido) {
        Pago p = new Pago();
        p.setPedido(pedido);
        p.setMonto(pedido.getTotal());
        p.setMetodo("DESCONOCIDO");
        return p;
    }
}

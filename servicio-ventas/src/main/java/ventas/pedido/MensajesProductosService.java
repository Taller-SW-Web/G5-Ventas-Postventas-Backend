package ventas.pedido;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import ventas.anulacion.AnulacionSistemaService;
import ventas.anulacion.ReembolsoAnulacionService;
import ventas.integracion.CuponesClient;
import ventas.integracion.MensajeInventario;
import ventas.integracion.PasarelaClient;
import ventas.integracion.ProductosClient;
import ventas.integracion.ProductosClient.MotivoLiberacion;
import ventas.integracion.ProductosClient.Operacion;
import ventas.integracion.ProductosClient.ResultadoConfirmacion;
import ventas.integracion.eventos.EventoRecibido;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;

/**
 * Aplica los mensajes de Productos (G6) que llegan a po.sales.q (o, en modo simulado, la reserva creada al instante).
 * Es idempotente: cada message_id se guarda en evento_recibido con origen SISTEMA_PRODUCTOS.
 *
 * | Mensaje                                  | Qué hace M1                                                         |
 * |------------------------------------------|---------------------------------------------------------------------|
 * | inventory.reservation.created            | Guarda reservaId. Con cupón: pide consumirlo; sin cupón, MARKETPLACE pide el cobro. PAGADO confirma; ANULADO libera |
 * | promotions.coupon.consumption.completed  | CREADO + MARKETPLACE: recién ahí pide el cobro (kit G6 §18: reserva -> cupón -> pago) |
 * | promotions.coupon.consumption.rejected   | CREADO -> ANULADO (CUPON_RECHAZADO) y libera la reserva; PAGADO: además reembolso |
 * | inventory.consumption.rejected (RESERVAR) | CREADO -> ANULADO (STOCK_NO_DISPONIBLE); PAGADO: además reembolso    |
 * | inventory.consumption.rejected (CONFIRMAR)| PAGADO -> ANULADO (RESERVA_EXPIRADA) + reembolso                     |
 * | inventory.reservation.expired            | CREADO -> ANULADO (RESERVA_EXPIRADA); en otro estado solo se registra |
 * | consumed / completed / released / restoration.* | Solo se registran                                            |
 * Orden de reemplazo (F3): la reserva creada la pasa CREADO -> EN_PREPARACION (no hay cobro); si la reserva se
 * rechaza o vence (también ya en preparación) se anula sin reembolso y M2 reabre la devolución.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MensajesProductosService {

    static final String MOTIVO_SIN_STOCK = "STOCK_NO_DISPONIBLE";
    static final String MOTIVO_RESERVA_EXPIRADA = "RESERVA_EXPIRADA";
    static final String MOTIVO_CUPON_RECHAZADO = "CUPON_RECHAZADO";
    static final String MOTIVO_REEMPLAZO_RESERVADO = "Reserva del reemplazo confirmada por Productos";

    /** PEDIDO_NO_ENCONTRADO: el consumidor reintenta (el pedido puede no haber terminado de guardarse). */
    public enum Desenlace { APLICADO, REPETIDO, IGNORADO, PEDIDO_NO_ENCONTRADO }

    /** Lo que hay que hacer fuera de la transacción (llamadas HTTP). */
    private enum Accion { NINGUNA, COBRAR, CONFIRMAR, LIBERAR, REEMBOLSAR, CONSUMIR_CUPON }

    /** dato: método de pago (COBRAR), reservaId (CONFIRMAR, LIBERAR) o código de la anulación (REEMBOLSAR). */
    private record Paso(Desenlace desenlace, Accion accion, Pedido pedido, String dato, String motivo) {
        static Paso solo(Desenlace d) {
            return new Paso(d, Accion.NINGUNA, null, null, null);
        }
    }

    private final PedidoRepository pedidos;
    private final PagoRepository pagos;
    private final EventoRecibidoRepository recibidos;
    private final MaquinaEstados maquina;
    private final AnulacionSistemaService anulaciones;
    private final ProductosClient productos;
    private final PasarelaClient pasarela;
    private final ReembolsoAnulacionService reembolsos;
    private final CuponesClient cupones;
    private final TransactionOperations transaccion;

    public Desenlace procesar(MensajeInventario m) {
        Paso paso;
        try {
            paso = transaccion.execute(estado -> aplicar(m));
        } catch (DataIntegrityViolationException e) {
            return Desenlace.REPETIDO;   // el mismo message_id llegó dos veces a la vez
        }
        ejecutar(paso);
        return paso.desenlace();
    }

    /**
     * Productos respondió 409 RESERVA_EXPIRADA al confirmar el consumo de un pedido ya PAGADO:
     * se anula y se devuelve el dinero (decisión 1 del equipo, 2026-10-12).
     */
    public void reservaExpiradaTrasPago(String pedidoId) {
        ejecutar(transaccion.execute(estado -> pedidos.findByCodigo(pedidoId)
                .filter(p -> p.getEstado() == EstadoPedido.PAGADO)
                .map(p -> anularPagado(p, MOTIVO_RESERVA_EXPIRADA))
                .orElse(Paso.solo(Desenlace.IGNORADO))));
    }

    /**
     * Productos respondió 409 RESERVA_EXPIRADA al consumir la reserva de un reemplazo (al sellar el paquete):
     * se anula sin reembolso y M2 devuelve la devolución a evaluación para pedir otro con una reserva nueva.
     */
    public void reservaExpiradaReemplazo(String pedidoId) {
        transaccion.executeWithoutResult(estado -> pedidos.findByCodigo(pedidoId)
                .filter(p -> p.esReemplazo() && p.getEstado() == EstadoPedido.EN_PREPARACION)
                .ifPresent(p -> anular(p, MOTIVO_RESERVA_EXPIRADA)));
    }

    private Paso aplicar(MensajeInventario m) {
        if (recibidos.existsByIdEventoAndOrigen(m.messageId(), OrigenEvento.SISTEMA_PRODUCTOS)) {
            return Paso.solo(Desenlace.REPETIDO);
        }
        if (m.pedidoId() == null) {
            log.warn("Mensaje {} ({}) sin order_id: se ignora", m.messageId(), m.name());
            return Paso.solo(Desenlace.IGNORADO);
        }
        Pedido pedido = pedidos.findByCodigo(m.pedidoId()).orElse(null);
        if (pedido == null) {
            return Paso.solo(Desenlace.PEDIDO_NO_ENCONTRADO);
        }
        recibidos.saveAndFlush(new EventoRecibido(m.messageId(), OrigenEvento.SISTEMA_PRODUCTOS, OffsetDateTime.now(ZoneOffset.UTC)));

        return switch (m.name()) {
            case MensajeInventario.RESERVA_CREADA -> reservaCreada(pedido, m.reservaId());
            case MensajeInventario.CONSUMO_RECHAZADO -> rechazado(pedido, m);
            case MensajeInventario.RESERVA_EXPIRADA -> expirada(pedido);
            case MensajeInventario.CUPON_CONSUMIDO -> cuponConsumido(pedido);
            case MensajeInventario.CUPON_RECHAZADO -> cuponRechazado(pedido, m);
            default -> new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);   // consumed, completed, released
        };
    }

    private Paso reservaCreada(Pedido pedido, String reservaId) {
        pedido.setReservaId(reservaId);
        pedidos.save(pedido);
        return switch (pedido.getEstado()) {
            // Con cupón, primero se consume (kit G6 §18); sin cupón, Marketplace cobra con la reserva confirmada
            // (AGENTS.md punto 8). Chatbot cobra por su cuenta
            case CREADO -> pedido.esReemplazo() ? reemplazoReservado(pedido) : tieneCupon(pedido)
                    ? new Paso(Desenlace.APLICADO, Accion.CONSUMIR_CUPON, pedido, null, null)
                    : cobrarSiEsMarketplace(pedido);
            // El chatbot pagó antes de que llegara la reserva: se consume ahora
            case PAGADO -> new Paso(Desenlace.APLICADO, Accion.CONFIRMAR, pedido, reservaId, null);
            // Se anuló mientras tanto (pago rechazado o F2): se devuelve el stock
            case ANULADO -> new Paso(Desenlace.APLICADO, Accion.LIBERAR, pedido, reservaId, null);
            default -> new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
        };
    }

    /** El reemplazo no se cobra: con el stock apartado ya se puede preparar (el consumo se confirma al sellar). */
    private Paso reemplazoReservado(Pedido pedido) {
        maquina.cambiar(pedido, EstadoPedido.EN_PREPARACION, ActorHistorial.SISTEMA_PRODUCTOS, MOTIVO_REEMPLAZO_RESERVADO);
        return new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
    }

    private Paso cobrarSiEsMarketplace(Pedido pedido) {
        return pedido.getCanal() == CanalVenta.MARKETPLACE
                ? new Paso(Desenlace.APLICADO, Accion.COBRAR, pedido,
                        pagos.findByPedidoId(pedido.getId()).map(Pago::getMetodo).orElse(null), null)
                : new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
    }

    /** Cupón consumido: recién ahora se habilita el pago (solo si el pedido sigue esperando el cobro). */
    private Paso cuponConsumido(Pedido pedido) {
        return pedido.getEstado() == EstadoPedido.CREADO
                ? cobrarSiEsMarketplace(pedido)
                : new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
    }

    /** El total ya incluía el descuento: sin cupón válido el pedido se anula (decisión del equipo, ESP-03). */
    private Paso cuponRechazado(Pedido pedido, MensajeInventario m) {
        log.info("Productos rechazó el cupón {} de {}: {}", pedido.getCodigoCupon(), pedido.getCodigo(), m.codigo());
        return switch (pedido.getEstado()) {
            case CREADO -> {
                maquina.cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS, MOTIVO_CUPON_RECHAZADO);
                yield pedido.getReservaId() == null
                        ? new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, MOTIVO_CUPON_RECHAZADO)
                        : new Paso(Desenlace.APLICADO, Accion.LIBERAR, pedido, pedido.getReservaId(), MOTIVO_CUPON_RECHAZADO);
            }
            case PAGADO -> anularPagado(pedido, MOTIVO_CUPON_RECHAZADO);   // el Chatbot ya cobró
            default -> new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
        };
    }

    /** Retail no reserva ni consume cupones por M1 (el POS va directo con Productos, acuerdo G4). */
    static boolean tieneCupon(Pedido pedido) {
        return pedido.getCodigoCupon() != null && !pedido.getCodigoCupon().isBlank()
                && pedido.getCanal() != CanalVenta.RETAIL;
    }

    private void consumirCupon(Pedido p) {
        cupones.consumir(p.getCodigo(), p.getCodigoCupon().trim(), p.getIdCliente(), p.getCanal().name(), p.getCreadoEn())
                .ifPresent(this::procesar);   // modo simulado: el "completed" se aplica al instante
    }

    /** El operation_id del sobre dice si falló la reserva o la confirmación del consumo. */
    private Paso rechazado(Pedido pedido, MensajeInventario m) {
        String codigo = pedido.getCodigo();
        if (ProductosClient.operacionId(Operacion.RESERVAR, codigo).equals(m.operationId())) {
            log.info("Productos rechazó la reserva de {}: {}", codigo, m.codigo());
            return switch (pedido.getEstado()) {
                case CREADO -> anular(pedido, MOTIVO_SIN_STOCK);
                case PAGADO -> anularPagado(pedido, MOTIVO_SIN_STOCK);   // el Chatbot ya cobró
                default -> new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
            };
        }
        if (ProductosClient.operacionId(Operacion.CONFIRMAR, codigo).equals(m.operationId())) {
            if (pedido.getEstado() == EstadoPedido.PAGADO) {
                log.info("Productos rechazó consumir la reserva de {}: {}", codigo, m.codigo());
                return anularPagado(pedido, MOTIVO_RESERVA_EXPIRADA);
            }
            if (reemplazoEnPreparacion(pedido)) {
                log.info("Productos rechazó consumir la reserva del reemplazo {}: {}", codigo, m.codigo());
                return anular(pedido, MOTIVO_RESERVA_EXPIRADA);
            }
        }
        log.warn("Rechazo {} de {} sin operación conocida (operation_id {})", m.codigo(), codigo, m.operationId());
        return new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
    }

    private Paso expirada(Pedido pedido) {
        if (pedido.getEstado() == EstadoPedido.CREADO || reemplazoEnPreparacion(pedido)) {
            return anular(pedido, MOTIVO_RESERVA_EXPIRADA);
        }
        return new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
    }

    private static boolean reemplazoEnPreparacion(Pedido pedido) {
        return pedido.esReemplazo() && pedido.getEstado() == EstadoPedido.EN_PREPARACION;
    }

    private Paso anular(Pedido pedido, String motivo) {
        maquina.cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS, motivo);
        return new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, motivo);
    }

    /** Ya cobrado: se registra la anulación (el reembolso exige su ANUL-NNNN), se anula y se pide el reembolso. */
    private Paso anularPagado(Pedido pedido, String motivo) {
        String anulacionId = anulaciones.registrarEjecutada(pedido.getId(), motivo);
        reembolsos.registrar(anulacionId, pedido.getId(), pedido.getTotal(), pedido.getMoneda().trim(),
                "Anulación automática de M1 por " + motivo);
        maquina.cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS, motivo);
        return new Paso(Desenlace.APLICADO, Accion.REEMBOLSAR, pedido, anulacionId, motivo);
    }

    private void ejecutar(Paso paso) {
        Pedido p = paso.pedido();
        switch (paso.accion()) {
            case COBRAR -> pasarela.iniciarCobro(p.getCodigo(), p.getTotal(), p.getMoneda().trim(), paso.dato());
            case CONFIRMAR -> {
                if (productos.confirmar(p.getCodigo(), paso.dato()) == ResultadoConfirmacion.RESERVA_EXPIRADA) {
                    reservaExpiradaTrasPago(p.getCodigo());
                } else if (tieneCupon(p)) {
                    consumirCupon(p);   // el Chatbot pagó antes de la reserva: el cupón se consume ahora
                }
            }
            case CONSUMIR_CUPON -> consumirCupon(p);
            case LIBERAR -> productos.liberar(p.getCodigo(), paso.dato(), MotivoLiberacion.ANULACION);
            case REEMBOLSAR -> reembolsos.enviar(paso.dato());   // si M2 falla, queda PENDIENTE y se reintenta
            case NINGUNA -> { }
        }
    }
}

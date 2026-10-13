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
 * | inventory.reservation.created            | Guarda reservaId. MARKETPLACE pide el cobro; PAGADO confirma; ANULADO libera |
 * | inventory.consumption.rejected (RESERVAR) | CREADO -> ANULADO (STOCK_NO_DISPONIBLE); PAGADO: además reembolso    |
 * | inventory.consumption.rejected (CONFIRMAR)| PAGADO -> ANULADO (RESERVA_EXPIRADA) + reembolso                     |
 * | inventory.reservation.expired            | CREADO -> ANULADO (RESERVA_EXPIRADA); en otro estado solo se registra |
 * | consumed / completed / released          | Solo se registran                                                   |
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MensajesProductosService {

    static final String MOTIVO_SIN_STOCK = "STOCK_NO_DISPONIBLE";
    static final String MOTIVO_RESERVA_EXPIRADA = "RESERVA_EXPIRADA";

    /** PEDIDO_NO_ENCONTRADO: el consumidor reintenta (el pedido puede no haber terminado de guardarse). */
    public enum Desenlace { APLICADO, REPETIDO, IGNORADO, PEDIDO_NO_ENCONTRADO }

    /** Lo que hay que hacer fuera de la transacción (llamadas HTTP). */
    private enum Accion { NINGUNA, COBRAR, CONFIRMAR, LIBERAR, REEMBOLSAR }

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
            default -> new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);   // consumed, completed, released
        };
    }

    private Paso reservaCreada(Pedido pedido, String reservaId) {
        pedido.setReservaId(reservaId);
        pedidos.save(pedido);
        return switch (pedido.getEstado()) {
            // Marketplace cobra con la reserva confirmada (AGENTS.md punto 8); Chatbot cobra por su cuenta
            case CREADO -> pedido.getCanal() == CanalVenta.MARKETPLACE
                    ? new Paso(Desenlace.APLICADO, Accion.COBRAR, pedido,
                            pagos.findByPedidoId(pedido.getId()).map(Pago::getMetodo).orElse(null), null)
                    : new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
            // El chatbot pagó antes de que llegara la reserva: se consume ahora
            case PAGADO -> new Paso(Desenlace.APLICADO, Accion.CONFIRMAR, pedido, reservaId, null);
            // Se anuló mientras tanto (pago rechazado o F2): se devuelve el stock
            case ANULADO -> new Paso(Desenlace.APLICADO, Accion.LIBERAR, pedido, reservaId, null);
            default -> new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
        };
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
        if (ProductosClient.operacionId(Operacion.CONFIRMAR, codigo).equals(m.operationId())
                && pedido.getEstado() == EstadoPedido.PAGADO) {
            log.info("Productos rechazó consumir la reserva de {}: {}", codigo, m.codigo());
            return anularPagado(pedido, MOTIVO_RESERVA_EXPIRADA);
        }
        log.warn("Rechazo {} de {} sin operación conocida (operation_id {})", m.codigo(), codigo, m.operationId());
        return new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
    }

    private Paso expirada(Pedido pedido) {
        if (pedido.getEstado() == EstadoPedido.CREADO) {
            return anular(pedido, MOTIVO_RESERVA_EXPIRADA);
        }
        return new Paso(Desenlace.APLICADO, Accion.NINGUNA, pedido, null, null);
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
                }
            }
            case LIBERAR -> productos.liberar(p.getCodigo(), paso.dato(), MotivoLiberacion.ANULACION);
            case REEMBOLSAR -> reembolsos.enviar(paso.dato());   // si M2 falla, queda PENDIENTE y se reintenta
            case NINGUNA -> { }
        }
    }
}

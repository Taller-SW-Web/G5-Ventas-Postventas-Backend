package ventas.pedido;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import ventas.integracion.PasarelaClient;
import ventas.integracion.ProductosClient;
import ventas.integracion.ProductosClient.ResultadoReserva;
import ventas.integracion.eventos.EventoRecibido;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;

/**
 * Aplica el resultado de una reserva de stock (acuerdo G6). Lo llama el consumidor de po.sales.q
 * o, en modo simulado, CrearPedidoService al instante. Es idempotente: cada resultado se guarda
 * en evento_recibido con origen SISTEMA_PRODUCTOS.
 * - Confirmada: guarda reservaId. MARKETPLACE recién ahí pide el cobro (AGENTS.md punto 8).
 * - Rechazada: CREADO -> ANULADO (actor SISTEMA_PRODUCTOS, motivo STOCK_NO_DISPONIBLE, provisional).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultadoReservaService {

    static final String MOTIVO_SIN_STOCK = "STOCK_NO_DISPONIBLE";

    public enum Desenlace { APLICADO, REPETIDO, PEDIDO_NO_ENCONTRADO }

    /** Lo que hay que hacer fuera de la transacción (llamadas HTTP). */
    private enum Accion { NINGUNA, COBRAR, CONFIRMAR, LIBERAR }

    private record Paso(Desenlace desenlace, Accion accion, Pedido pedido, String metodoPago) {}

    private final PedidoRepository pedidos;
    private final PagoRepository pagos;
    private final EventoRecibidoRepository recibidos;
    private final MaquinaEstados maquina;
    private final ProductosClient productos;
    private final PasarelaClient pasarela;
    private final TransactionOperations transaccion;

    /**
     * PEDIDO_NO_ENCONTRADO: puede ser que el pedido aún no termina de guardarse (el consumidor debe
     * reintentar) o que no se guardó nunca; en ese caso, al agotar los reintentos, llamar a {@link #huerfana}.
     */
    public Desenlace procesar(ResultadoReserva r) {
        Paso paso;
        try {
            paso = transaccion.execute(estado -> aplicar(r));
        } catch (DataIntegrityViolationException e) {
            return Desenlace.REPETIDO;   // el mismo resultado llegó dos veces a la vez
        }
        Pedido p = paso.pedido();
        switch (paso.accion()) {
            case COBRAR -> pasarela.iniciarCobro(p.getCodigo(), p.getTotal(), p.getMoneda().trim(), paso.metodoPago());
            case CONFIRMAR -> productos.confirmar(r.reservaId());
            case LIBERAR -> productos.liberar(r.reservaId());
            case NINGUNA -> { }
        }
        return paso.desenlace();
    }

    /** Reserva confirmada cuyo pedido no se llegó a guardar: se devuelve el stock. */
    public void huerfana(ResultadoReserva r) {
        if (r.confirmada() && r.reservaId() != null) {
            log.warn("Reserva {} sin pedido (referencia {}): se libera", r.reservaId(), r.referencia());
            productos.liberar(r.reservaId());
        }
    }

    private Paso aplicar(ResultadoReserva r) {
        String idEvento = "reserva:" + r.referencia();
        if (recibidos.existsByIdEventoAndOrigen(idEvento, OrigenEvento.SISTEMA_PRODUCTOS)) {
            return new Paso(Desenlace.REPETIDO, Accion.NINGUNA, null, null);
        }
        Pedido pedido = pedidos.findByIdempotencyKey(r.referencia()).orElse(null);
        if (pedido == null) {
            return new Paso(Desenlace.PEDIDO_NO_ENCONTRADO, Accion.NINGUNA, null, null);
        }
        recibidos.saveAndFlush(new EventoRecibido(idEvento, OrigenEvento.SISTEMA_PRODUCTOS, OffsetDateTime.now(ZoneOffset.UTC)));

        if (!r.confirmada()) {
            return new Paso(Desenlace.APLICADO, Accion.NINGUNA, rechazada(pedido), null);
        }
        pedido.setReservaId(r.reservaId());
        pedidos.save(pedido);
        Accion accion = switch (pedido.getEstado()) {
            // Marketplace cobra con la reserva confirmada; Chatbot cobra por su cuenta
            case CREADO -> pedido.getCanal() == CanalVenta.MARKETPLACE ? Accion.COBRAR : Accion.NINGUNA;
            // El chatbot pagó antes de que llegara la reserva: se consume ahora
            case PAGADO -> Accion.CONFIRMAR;
            // Se anuló mientras tanto (pago rechazado o F2): se devuelve el stock
            case ANULADO -> Accion.LIBERAR;
            default -> Accion.NINGUNA;
        };
        String metodoPago = accion == Accion.COBRAR
                ? pagos.findByPedidoId(pedido.getId()).map(Pago::getMetodo).orElse(null)
                : null;
        return new Paso(Desenlace.APLICADO, accion, pedido, metodoPago);
    }

    private Pedido rechazada(Pedido pedido) {
        if (pedido.getEstado() == EstadoPedido.CREADO) {
            maquina.cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS, MOTIVO_SIN_STOCK);
        } else {
            // Ya pagado sin stock: anular y reembolsar es decisión del equipo; por ahora queda para revisión
            log.warn("Reserva rechazada para {} en estado {}: requiere revisión manual",
                    pedido.getCodigo(), pedido.getEstado());
        }
        return pedido;
    }
}

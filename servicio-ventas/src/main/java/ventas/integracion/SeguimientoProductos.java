package ventas.integracion;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ventas.integracion.CuponesClient.ResultadoCupon;
import ventas.integracion.ProductosClient.Operacion;
import ventas.integracion.ProductosClient.ResultadoOperacion;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.CanalVenta;
import ventas.pedido.EstadoPedido;
import ventas.pedido.MensajesProductosService;
import ventas.pedido.Pedido;
import ventas.pedido.PedidoRepository;
import ventas.pedido.TipoPedido;

/**
 * Contrato H5 con Productos (G6): reemplaza los mensajes que llegaban por RabbitMQ (po.sales.q) por consultas HTTP
 * (contrato G5-G6-H5 §6.4, §7.3, §7.4 y §11). Cada barrido recorre los pedidos que pueden tener algo pendiente y, por
 * cada resultado terminal, arma el mismo MensajeInventario que antes llegaba por la cola y lo aplica con
 * MensajesProductosService (que ya resuelve estados, reembolsos e idempotencia).
 *
 * | Pedido                                   | Consulta                                         | Mensaje equivalente            |
 * |------------------------------------------|--------------------------------------------------|--------------------------------|
 * | Sin reservaId (CREADO o PAGADO)          | GET /inventario/operaciones/{op RESERVAR}        | reservation.created / consumption.rejected |
 * | CREADO con cupón y reserva               | GET /cupones/operaciones/{op CUPON}?order_id=     | coupon.consumption.completed / rejected; 404 = se reenvía el POST |
 * | CREADO o reemplazo EN_PREPARACION        | GET /inventario/reservas/{id}/estado?order_id=    | reservation.expired (VENCIDA o EXPIRADA) |
 * | PAGADO o reemplazo EN_PREPARACION        | GET /inventario/operaciones/{op CONFIRMAR}        | consumption.completed / rejected |
 *
 * Seguimiento durable (contrato H5 §8.2): no hay estado en memoria. Lo pendiente se deduce de los pedidos en base de
 * datos y cada resultado aplicado queda en evento_recibido (id "H5:..."), así que tras un reinicio el siguiente
 * barrido continúa donde quedó y nunca aplica dos veces el mismo resultado. Un error de red solo deja la consulta
 * para el siguiente barrido; nunca se toma como éxito ni como rechazo (§9).
 * Cadencia (contrato H5 D-02): los resultados de operaciones se consultan cada 5 s (dentro del máximo de 30 s del
 * contrato) y la vigencia de las reservas, que cambia en minutos, cada 60 s.
 * Solo existe con integracion.simulado=false e integracion.productos-transporte=http.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${integracion.simulado:true}' == 'false' and '${integracion.productos-transporte:http}' == 'http'")
public class SeguimientoProductos {

    /** Estados con algo pendiente; además, las órdenes de reemplazo EN_PREPARACION (ver PedidoRepository). */
    static final List<EstadoPedido> ESTADOS = List.of(EstadoPedido.CREADO, EstadoPedido.PAGADO);

    private final PedidoRepository pedidos;
    private final EventoRecibidoRepository recibidos;
    private final ProductosClient productos;
    private final CuponesClient cupones;
    private final MensajesProductosService mensajes;
    private final Clock reloj;
    private final Duration ventana;
    private final int lote;
    private final Duration cadaVencimiento;
    private OffsetDateTime proximoVencimiento;

    @Autowired
    public SeguimientoProductos(PedidoRepository pedidos, EventoRecibidoRepository recibidos, ProductosClient productos,
                                CuponesClient cupones, MensajesProductosService mensajes,
                                @Value("${integracion.productos-seguimiento.ventana-horas:72}") long ventanaHoras,
                                @Value("${integracion.productos-seguimiento.lote:100}") int lote,
                                @Value("${integracion.productos-seguimiento.vencimiento-ms:60000}") long vencimientoMs) {
        this(pedidos, recibidos, productos, cupones, mensajes, Clock.systemUTC(), Duration.ofHours(ventanaHoras), lote,
                Duration.ofMillis(vencimientoMs));
    }

    SeguimientoProductos(PedidoRepository pedidos, EventoRecibidoRepository recibidos, ProductosClient productos,
                         CuponesClient cupones, MensajesProductosService mensajes, Clock reloj, Duration ventana, int lote,
                         Duration cadaVencimiento) {
        this.pedidos = pedidos;
        this.recibidos = recibidos;
        this.productos = productos;
        this.cupones = cupones;
        this.mensajes = mensajes;
        this.reloj = reloj;
        this.ventana = ventana;
        this.lote = lote;
        this.cadaVencimiento = cadaVencimiento;
    }

    @Scheduled(fixedDelayString = "${integracion.productos-seguimiento.intervalo-ms:5000}",
               initialDelayString = "${integracion.productos-seguimiento.inicio-ms:20000}")
    public void barrer() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        boolean revisarVencimientos = proximoVencimiento == null || !ahora.isBefore(proximoVencimiento);
        if (revisarVencimientos) {
            proximoVencimiento = ahora.plus(cadaVencimiento);
        }
        List<Pedido> pendientes = pedidos.enSeguimientoProductos(ESTADOS, EstadoPedido.EN_PREPARACION,
                TipoPedido.REEMPLAZO, CanalVenta.RETAIL, ahora.minus(ventana), PageRequest.of(0, lote));
        for (Pedido p : pendientes) {
            try {
                seguir(p, revisarVencimientos);
            } catch (RuntimeException e) {
                // Un pedido con problemas no detiene a los demás; se reintenta en el siguiente barrido
                log.warn("Seguimiento H5 de {} falló: {}", p.getCodigo(), e.getMessage());
            }
        }
    }

    void seguir(Pedido p) {
        seguir(p, true);
    }

    void seguir(Pedido p, boolean revisarVencimiento) {
        String codigo = p.getCodigo();
        EstadoPedido estado = p.getEstado();
        boolean reemplazoEnPreparacion = p.esReemplazo() && estado == EstadoPedido.EN_PREPARACION;

        if (p.getReservaId() == null) {
            if (estado == EstadoPedido.CREADO || estado == EstadoPedido.PAGADO) {
                resultadoReserva(codigo);
            }
            return;
        }
        if (estado == EstadoPedido.CREADO && tieneCupon(p)) {
            resultadoCupon(p);
        }
        if (revisarVencimiento && (estado == EstadoPedido.CREADO || reemplazoEnPreparacion)) {
            vencimiento(codigo, p.getReservaId());
        }
        if (estado == EstadoPedido.PAGADO || reemplazoEnPreparacion) {
            resultadoConsumo(codigo);
        }
    }

    /** Crear reserva (§6.1 y §6.4): COMPLETED trae reservation_id; REJECTED, el código del rechazo. */
    private void resultadoReserva(String codigo) {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, codigo);
        if (resuelta(op)) {
            return;
        }
        productos.consultarOperacion(op).ifPresent(r -> {
            if (r.completada() && r.reservationId() != null) {
                aplicar(op, "COMPLETED", MensajeInventario.RESERVA_CREADA, codigo,
                        Map.of(MensajeInventario.CAMPO_RESERVA, r.reservationId()));
            } else if (r.rechazada()) {
                aplicar(op, "REJECTED", MensajeInventario.CONSUMO_RECHAZADO, codigo, codigoRechazo(r));
            } else if ("REQUIRES_REVIEW".equals(r.status())) {
                log.warn("Reserva de {} en REQUIRES_REVIEW: requiere conciliación con Productos", codigo);
            }
        });
    }

    /** Confirmar consumo (§6.2 y §6.4). */
    private void resultadoConsumo(String codigo) {
        String op = ProductosClient.operacionId(Operacion.CONFIRMAR, codigo);
        if (resuelta(op)) {
            return;
        }
        productos.consultarOperacion(op).ifPresent(r -> {
            if (r.completada()) {
                aplicar(op, "COMPLETED", MensajeInventario.CONSUMO_COMPLETADO, codigo, Map.of());
            } else if (r.rechazada()) {
                aplicar(op, "REJECTED", MensajeInventario.CONSUMO_RECHAZADO, codigo, codigoRechazo(r));
            } else if ("REQUIRES_REVIEW".equals(r.status())) {
                log.warn("Consumo de {} en REQUIRES_REVIEW: requiere conciliación con Productos", codigo);
            }
        });
    }

    /**
     * Consumo de cupón (§7.1 y §7.3). Si Productos responde 404 (no conoce la operación), se reenvía con la misma
     * identidad; si no responde, solo se espera al siguiente barrido.
     */
    private void resultadoCupon(Pedido p) {
        String codigo = p.getCodigo();
        String op = CuponesClient.operacionConsumo(codigo);
        if (resuelta(op)) {
            return;
        }
        var resultado = cupones.consultar(op, codigo);
        if (resultado.isEmpty()) {
            return;   // sin respuesta: se vuelve a preguntar en el siguiente barrido, sin reenviar
        }
        if (resultado.get().noEncontrada()) {
            cupones.consumir(codigo, p.getCodigoCupon().trim(), p.getIdCliente(), p.getCanal().name(), p.getCreadoEn(),
                    p.getReservaId());
            return;
        }
        ResultadoCupon r = resultado.get();
        if (r.completada()) {
            aplicar(op, "COMPLETED", MensajeInventario.CUPON_CONSUMIDO, codigo, Map.of());
        } else if (r.rechazada()) {
            Map<String, Object> data = new HashMap<>();
            data.put(MensajeInventario.CAMPO_CODIGO, r.resultCode());
            aplicar(op, "REJECTED", MensajeInventario.CUPON_RECHAZADO, codigo, data);
        }
    }

    /** Expiración espontánea (§7.4 y §8.2): ACTIVA vencida o EXPIRADA persistida anulan el pedido pendiente. */
    private void vencimiento(String codigo, String reservaId) {
        String id = "H5:EXP:" + reservaId;
        if (recibidos.existsByIdEventoAndOrigen(id, OrigenEvento.SISTEMA_PRODUCTOS)) {
            return;
        }
        productos.consultarReserva(reservaId, codigo)
                .filter(ProductosClient.EstadoReserva::vencida)
                .ifPresent(e -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put(MensajeInventario.CAMPO_PEDIDO, codigo);
                    data.put(MensajeInventario.CAMPO_RESERVA, reservaId);
                    mensajes.procesar(new MensajeInventario(id, MensajeInventario.RESERVA_EXPIRADA, null, data));
                });
    }

    private void aplicar(String op, String status, String nombre, String codigo, Map<String, Object> datos) {
        Map<String, Object> data = new HashMap<>(datos);
        data.put(MensajeInventario.CAMPO_PEDIDO, codigo);
        mensajes.procesar(new MensajeInventario(idResultado(op, status), nombre, op, data));
    }

    /** Ya se aplicó un resultado terminal de esta operación: no se vuelve a consultar. */
    private boolean resuelta(String op) {
        return recibidos.existsByIdEventoAndOrigen(idResultado(op, "COMPLETED"), OrigenEvento.SISTEMA_PRODUCTOS)
                || recibidos.existsByIdEventoAndOrigen(idResultado(op, "REJECTED"), OrigenEvento.SISTEMA_PRODUCTOS);
    }

    static String idResultado(String op, String status) {
        return "H5:" + op + ":" + status;
    }

    private static Map<String, Object> codigoRechazo(ResultadoOperacion r) {
        Map<String, Object> data = new HashMap<>();
        data.put(MensajeInventario.CAMPO_CODIGO, r.resultCode());
        return data;
    }

    /** Retail no reserva ni consume cupones por M1 (acuerdo G4); el resto sí. */
    private static boolean tieneCupon(Pedido p) {
        return p.getCodigoCupon() != null && !p.getCodigoCupon().isBlank() && p.getCanal() != CanalVenta.RETAIL;
    }
}

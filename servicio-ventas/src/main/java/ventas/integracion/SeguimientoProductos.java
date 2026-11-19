package ventas.integracion;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ventas.integracion.CuponesClient.ResultadoCupon;
import ventas.integracion.ProductosClient.Operacion;
import ventas.integracion.ProductosClient.ResultadoConfirmacion;
import ventas.integracion.ProductosClient.ResultadoOperacion;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.compartido.DependenciaNoDisponibleException;
import ventas.pedido.CanalVenta;
import ventas.pedido.DetallePedido;
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
 * | Pedido                                   | Consulta                                         | Mensaje equivalente / 404 |
 * |------------------------------------------|--------------------------------------------------|---------------------------|
 * | Sin reservaId (CREADO o PAGADO)          | GET /inventario/operaciones/{op RESERVAR}        | reservation.created / consumption.rejected; 404 = se reenvía la reserva |
 * | CREADO con cupón y reserva               | GET /cupones/operaciones/{op CUPON}?order_id=     | coupon.consumption.completed / rejected; 404 = se reenvía el consumo |
 * | CREADO o reemplazo EN_PREPARACION        | GET /inventario/reservas/{id}/estado?order_id=    | reservation.expired (VENCIDA o EXPIRADA) |
 * | PAGADO o reemplazo EN_PREPARACION        | GET /inventario/operaciones/{op CONFIRMAR}        | consumption.completed / rejected; 404 (venta PAGADO) = se reenvía la confirmación |
 *
 * Seguimiento durable (contrato H5 §8.2 y D-07): lo pendiente se deduce de los pedidos en base de datos y cada
 * resultado aplicado queda en evento_recibido (id "H5:..."), así que tras un reinicio el siguiente barrido continúa
 * donde quedó y nunca aplica dos veces el mismo resultado. Si Productos responde 404 (no conoce la operación: el POST
 * se perdió), se reenvía con la misma identidad y el mismo contenido, reconstruido del pedido guardado. Un error de
 * red solo deja la consulta para más tarde; nunca se toma como éxito ni como rechazo (§9).
 *
 * Cadencia por operación (contrato H5 D-02): primera consulta a los 2 s, luego backoff exponencial con jitter
 * (2, 4, 8, 16 y 30 s como máximo); pasados 2 minutos sin resultado terminal, cada 60 s. Un solo hilo: nunca hay dos
 * consultas a la vez de la misma operación. 429: se pausa todo el seguimiento lo que diga Retry-After. La vigencia de
 * las reservas se revisa cada 60 s. El plan de cada operación vive en memoria: tras un reinicio vuelve a empezar en 2 s.
 * Solo existe con integracion.simulado=false e integracion.productos-transporte=http.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${integracion.simulado:true}' == 'false' and '${integracion.productos-transporte:http}' == 'http'")
public class SeguimientoProductos {

    /** Estados con algo pendiente; además, las órdenes de reemplazo EN_PREPARACION (ver PedidoRepository). */
    static final List<EstadoPedido> ESTADOS = List.of(EstadoPedido.CREADO, EstadoPedido.PAGADO);
    static final Duration PRIMERA_CONSULTA = Duration.ofSeconds(2);
    static final Duration DEADLINE_EN_LINEA = Duration.ofMinutes(2);
    static final long MAXIMO_BACKOFF_S = 30;
    static final long TRAS_DEADLINE_S = 60;
    static final Duration PAUSA_429_POR_DEFECTO = Duration.ofSeconds(30);
    private static final int MAXIMO_RESUELTAS = 10_000;

    /** Plan de consulta de una operación pendiente. */
    record Plan(OffsetDateTime inicio, int intentos, OffsetDateTime proxima) {}

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
    private OffsetDateTime pausaHasta;
    private final Map<String, Plan> planes = new ConcurrentHashMap<>();
    /** Operaciones con resultado terminal ya aplicado: no se vuelven a consultar ni a buscar en la base. */
    private final Set<String> resueltas = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAXIMO_RESUELTAS;
        }
    });

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

    @Scheduled(fixedDelayString = "${integracion.productos-seguimiento.intervalo-ms:1000}",
               initialDelayString = "${integracion.productos-seguimiento.inicio-ms:20000}")
    public void barrer() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        if (pausaHasta != null && ahora.isBefore(pausaHasta)) {
            return;   // 429 de Productos: se respeta Retry-After
        }
        boolean revisarVencimientos = proximoVencimiento == null || !ahora.isBefore(proximoVencimiento);
        if (revisarVencimientos) {
            proximoVencimiento = ahora.plus(cadaVencimiento);
        }
        List<Pedido> pendientes = pedidos.enSeguimientoProductos(ESTADOS, EstadoPedido.EN_PREPARACION,
                TipoPedido.REEMPLAZO, CanalVenta.RETAIL, ahora.minus(ventana), PageRequest.of(0, lote));
        for (Pedido p : pendientes) {
            try {
                seguir(p, revisarVencimientos);
            } catch (LimiteProductosException e) {
                pausaHasta = ahora.plus(e.reintentarEn() != null ? e.reintentarEn() : PAUSA_429_POR_DEFECTO);
                log.warn("Productos respondió 429: seguimiento H5 en pausa hasta {}", pausaHasta);
                return;
            } catch (RuntimeException e) {
                // Un pedido con problemas no detiene a los demás; se reintenta en el siguiente barrido
                log.warn("Seguimiento H5 de {} falló: {}", p.getCodigo(), e.getMessage());
            }
        }
        // Planes de pedidos que ya salieron de la ventana (anulados, entregados, viejos): no se consultan más
        planes.values().removeIf(plan -> plan.inicio().isBefore(ahora.minus(ventana)));
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
                resultadoReserva(p);
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
            resultadoConsumo(p);
        }
    }

    /**
     * Crear reserva (§6.1 y §6.4): COMPLETED trae reservation_id; REJECTED, el código del rechazo. 404: el POST no
     * llegó (timeout o respuesta perdida al crear el pedido, D-07): se reenvía con la misma identidad y las mismas líneas.
     */
    private void resultadoReserva(Pedido p) {
        String codigo = p.getCodigo();
        String op = ProductosClient.operacionId(Operacion.RESERVAR, codigo);
        if (!toca(op)) {
            return;
        }
        var resultado = productos.consultarOperacion(op);
        if (resultado.isEmpty()) {
            reprogramar(op);
            return;
        }
        ResultadoOperacion r = resultado.get();
        if (r.noEncontrada()) {
            reenviarReserva(p);
            reprogramar(op);
        } else if (r.completada() && r.reservationId() != null) {
            aplicar(op, "COMPLETED", MensajeInventario.RESERVA_CREADA, codigo,
                    Map.of(MensajeInventario.CAMPO_RESERVA, r.reservationId()));
        } else if (r.rechazada()) {
            aplicar(op, "REJECTED", MensajeInventario.CONSUMO_RECHAZADO, codigo, codigoRechazo(r));
        } else if ("REQUIRES_REVIEW".equals(r.status())) {
            revision(op, "Reserva", codigo);
        } else {
            reprogramar(op);   // ACCEPTED o PROCESSING
        }
    }

    /**
     * Confirmar consumo (§6.2 y §6.4). 404 en una venta PAGADO: la confirmación no llegó, se reenvía igual. En las
     * órdenes de reemplazo no, porque se confirman recién al sellar el paquete.
     */
    private void resultadoConsumo(Pedido p) {
        String codigo = p.getCodigo();
        String op = ProductosClient.operacionId(Operacion.CONFIRMAR, codigo);
        if (!toca(op)) {
            return;
        }
        var resultado = productos.consultarOperacion(op);
        if (resultado.isEmpty()) {
            reprogramar(op);
            return;
        }
        ResultadoOperacion r = resultado.get();
        if (r.noEncontrada()) {
            if (p.getEstado() == EstadoPedido.PAGADO && !p.esReemplazo()) {
                log.info("Productos no conoce la confirmación de {}: se reenvía con la misma identidad", codigo);
                if (productos.confirmar(codigo, p.getReservaId()) == ResultadoConfirmacion.RESERVA_EXPIRADA) {
                    mensajes.reservaExpiradaTrasPago(codigo);
                    marcarResuelta(op);
                    return;
                }
            }
            reprogramar(op);
        } else if (r.completada()) {
            aplicar(op, "COMPLETED", MensajeInventario.CONSUMO_COMPLETADO, codigo, Map.of());
        } else if (r.rechazada()) {
            aplicar(op, "REJECTED", MensajeInventario.CONSUMO_RECHAZADO, codigo, codigoRechazo(r));
        } else if ("REQUIRES_REVIEW".equals(r.status())) {
            revision(op, "Consumo", codigo);
        } else {
            reprogramar(op);
        }
    }

    /**
     * Consumo de cupón (§7.1 y §7.3). Si Productos responde 404 (no conoce la operación), se reenvía con la misma
     * identidad; si no responde, solo se espera a la siguiente consulta.
     */
    private void resultadoCupon(Pedido p) {
        String codigo = p.getCodigo();
        String op = CuponesClient.operacionConsumo(codigo);
        if (!toca(op)) {
            return;
        }
        var resultado = cupones.consultar(op, codigo);
        if (resultado.isEmpty()) {
            reprogramar(op);   // sin respuesta: se vuelve a preguntar más tarde, sin reenviar
            return;
        }
        ResultadoCupon r = resultado.get();
        if (r.noEncontrada()) {
            cupones.consumir(codigo, p.getCodigoCupon().trim(), p.getIdCliente(), p.getCanal().name(), p.getCreadoEn(),
                    p.getReservaId());
            reprogramar(op);
        } else if (r.completada()) {
            aplicar(op, "COMPLETED", MensajeInventario.CUPON_CONSUMIDO, codigo, Map.of());
        } else if (r.rechazada()) {
            Map<String, Object> data = new HashMap<>();
            data.put(MensajeInventario.CAMPO_CODIGO, r.resultCode());
            aplicar(op, "REJECTED", MensajeInventario.CUPON_RECHAZADO, codigo, data);
        } else {
            reprogramar(op);
        }
    }

    /** Reenvío D-07: mismas líneas (en el orden en que se guardaron) y mismo canal que el POST original. */
    private void reenviarReserva(Pedido p) {
        Pedido conDetalles = pedidos.findConDetallesByCodigo(p.getCodigo()).orElse(null);
        if (conDetalles == null || conDetalles.getDetalles().isEmpty()) {
            return;
        }
        List<ProductosClient.LineaReserva> lineas = conDetalles.getDetalles().stream()
                .sorted(Comparator.comparing(DetallePedido::getId))
                .map(d -> new ProductosClient.LineaReserva(d.getSku(), d.getCantidad()))
                .toList();
        log.info("Productos no conoce la reserva de {}: se reenvía con la misma identidad", p.getCodigo());
        try {
            productos.reservar(p.getCodigo(), p.getCanal().name(), lineas);
        } catch (DependenciaNoDisponibleException e) {
            log.warn("Reenvío de la reserva de {} no admitido: {}", p.getCodigo(), e.getMessage());
        }
    }

    /** REQUIRES_REVIEW no es éxito (D-02): el pedido no cambia y la operación queda para revisión humana. */
    private void revision(String op, String que, String codigo) {
        log.warn("{} de {} en REQUIRES_REVIEW (operation_id {}): requiere conciliación con Productos", que, codigo, op);
        marcarResuelta(op);
    }

    /** ¿Ya toca consultar esta operación? La primera vez solo crea su plan: primera consulta a los 2 s. */
    boolean toca(String op) {
        if (resueltas.contains(op)) {
            return false;
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        Plan plan = planes.get(op);
        if (plan == null) {
            planes.put(op, new Plan(ahora, 0, ahora.plus(PRIMERA_CONSULTA)));
            return false;
        }
        if (ahora.isBefore(plan.proxima())) {
            return false;
        }
        if (resuelta(op)) {
            marcarResuelta(op);
            return false;
        }
        return true;
    }

    private void reprogramar(String op) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        Plan plan = planes.getOrDefault(op, new Plan(ahora, 0, ahora));
        int intentos = plan.intentos() + 1;
        planes.put(op, new Plan(plan.inicio(), intentos, ahora.plus(espera(plan.inicio(), intentos, ahora))));
    }

    /** 2, 4, 8, 16, 30, 30... s con hasta 1 s de jitter; pasado el deadline en línea de 2 min, 60 s. */
    static Duration espera(OffsetDateTime inicio, int intentos, OffsetDateTime ahora) {
        if (Duration.between(inicio, ahora).compareTo(DEADLINE_EN_LINEA) > 0) {
            return Duration.ofSeconds(TRAS_DEADLINE_S);
        }
        long base = Math.min(MAXIMO_BACKOFF_S, 2L << Math.min(intentos - 1, 4));
        return Duration.ofSeconds(base).plusMillis(ThreadLocalRandom.current().nextLong(0, 1000));
    }

    Plan plan(String op) {
        return planes.get(op);
    }

    private void marcarResuelta(String op) {
        planes.remove(op);
        resueltas.add(op);
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
        marcarResuelta(op);
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

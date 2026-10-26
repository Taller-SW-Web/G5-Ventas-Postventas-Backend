package ventas.pedido;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ventas.compartido.SolicitudInvalidaException;
import ventas.pedido.dto.KpisVentasResponse;
import ventas.pedido.dto.ListadoPedidosResponse;
import ventas.pedido.dto.MisPedidosResponse;
import ventas.pedido.dto.PedidoResumen;

/** Consultas de pedidos (F1, ESP-02). */
@Service
public class PedidoService {

    static final int TAMANO_MAXIMO = 50;
    /** 1.14: un año como máximo, para acotar la serie diaria. */
    static final int DIAS_MAXIMOS_KPIS = 366;

    private final PedidoRepository pedidos;

    public PedidoService(PedidoRepository pedidos) {
        this.pedidos = pedidos;
    }

    /**
     * 1.4b: pedidos del cliente autenticado. El clienteId sale del claim sub del token,
     * nunca de la URL. page empieza en 1; pageSize entre 1 y 50.
     */
    @Transactional(readOnly = true)
    public MisPedidosResponse listarMios(UUID clienteId, EstadoPedido estado, int page, int pageSize) {
        if (page < 1) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA", "page debe ser 1 o mayor");
        }
        if (pageSize < 1 || pageSize > TAMANO_MAXIMO) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA",
                    "pageSize debe estar entre 1 y " + TAMANO_MAXIMO);
        }
        Pageable pagina = PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Order.desc("creadoEn"), Sort.Order.desc("id")));
        Page<Pedido> resultado = estado == null
                ? pedidos.findByIdCliente(clienteId, pagina)
                : pedidos.findByIdClienteAndEstado(clienteId, estado, pagina);
        return new MisPedidosResponse(
                resultado.getContent().stream().map(PedidoService::resumen).toList(),
                resultado.getTotalElements(), page, pageSize);
    }

    /**
     * 1.4: listado para el Gestor (rol ADMIN_VENTAS). Todos los filtros son opcionales: sin clienteId lista todos
     * los pedidos (bandeja del mockup 02). pagina empieza en 0; tamano entre 1 y 50. Por defecto, más recientes.
     */
    @Transactional(readOnly = true)
    public ListadoPedidosResponse listar(UUID clienteId, EstadoPedido estado, CanalVenta canal, String buscar,
                                         LocalDate desde, LocalDate hasta, PedidosFiltro.Orden orden,
                                         int pagina, int tamano) {
        if (pagina < 0) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA", "pagina debe ser 0 o mayor");
        }
        if (tamano < 1 || tamano > TAMANO_MAXIMO) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA", "tamano debe estar entre 1 y " + TAMANO_MAXIMO);
        }
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new SolicitudInvalidaException("PARAMETRO_INVALIDO", "desde no puede ser posterior a hasta");
        }
        Pageable pageable = PageRequest.of(pagina, tamano, PedidosFiltro.orden(orden));
        Page<Pedido> resultado = pedidos.findAll(
                PedidosFiltro.de(clienteId, estado, canal, buscar, desde, hasta), pageable);
        return new ListadoPedidosResponse(clienteId,
                resultado.getContent().stream().map(PedidoService::resumen).toList(),
                pagina, resultado.getTotalPages(), resultado.getTotalElements());
    }

    /**
     * 1.14: KPIs de ventas del Dashboard (mockup 01), sumados aquí para que el navegador no pagine toda la bandeja.
     * Mismo criterio que la bandeja: pedidos de venta (no reemplazos) creados entre desde y hasta (UTC, inclusive).
     * Vendido = PAGADO, EN_PREPARACION, DESPACHADO o ENTREGADO; los CREADO (sin pagar) no cuentan en pedidosTotal.
     */
    @Transactional(readOnly = true)
    public KpisVentasResponse kpis(LocalDate desde, LocalDate hasta, CanalVenta canal) {
        if (desde == null || hasta == null) {
            throw new SolicitudInvalidaException("PARAMETRO_INVALIDO", "desde y hasta son obligatorios");
        }
        if (desde.isAfter(hasta)) {
            throw new SolicitudInvalidaException("PARAMETRO_INVALIDO", "desde no puede ser posterior a hasta");
        }
        if (ChronoUnit.DAYS.between(desde, hasta) >= DIAS_MAXIMOS_KPIS) {
            throw new SolicitudInvalidaException("PARAMETRO_INVALIDO",
                    "El rango no puede superar " + DIAS_MAXIMOS_KPIS + " días");
        }
        OffsetDateTime inicio = desde.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime fin = hasta.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<FilaKpi> filas = canal == null
                ? pedidos.findByTipoAndCreadoEnGreaterThanEqualAndCreadoEnLessThan(TipoPedido.VENTA, inicio, fin)
                : pedidos.findByTipoAndCanalAndCreadoEnGreaterThanEqualAndCreadoEnLessThan(TipoPedido.VENTA, canal, inicio, fin);

        Map<LocalDate, BigDecimal> porDia = new LinkedHashMap<>();
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) {
            porDia.put(d, BigDecimal.ZERO);
        }
        Map<CanalVenta, BigDecimal> porCanal = new EnumMap<>(CanalVenta.class);
        for (CanalVenta c : CanalVenta.values()) {
            porCanal.put(c, BigDecimal.ZERO);
        }
        BigDecimal monto = BigDecimal.ZERO;
        long vendidos = 0;
        long total = 0;
        long entregados = 0;
        long anulados = 0;
        for (FilaKpi f : filas) {
            if (f.getEstado() == EstadoPedido.CREADO) {
                continue;
            }
            total++;
            if (f.getEstado() == EstadoPedido.ENTREGADO) {
                entregados++;
            }
            if (f.getEstado() == EstadoPedido.ANULADO) {
                anulados++;
                continue;
            }
            vendidos++;
            monto = monto.add(f.getTotal());
            porCanal.merge(f.getCanal(), f.getTotal(), BigDecimal::add);
            LocalDate dia = f.getCreadoEn().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
            porDia.merge(dia, f.getTotal(), BigDecimal::add);
        }
        BigDecimal ticket = vendidos == 0 ? null
                : monto.divide(BigDecimal.valueOf(vendidos), 2, RoundingMode.HALF_UP);
        List<KpisVentasResponse.VentaDia> serie = porDia.entrySet().stream()
                .map(e -> new KpisVentasResponse.VentaDia(e.getKey(), e.getValue()))
                .toList();
        return new KpisVentasResponse(desde, hasta, canal, monto, vendidos, ticket, total, entregados, anulados,
                serie, porCanal);
    }

    static PedidoResumen resumen(Pedido p) {
        return new PedidoResumen(p.getCodigo(), p.getCanal(), p.getEstado(),
                p.getTotal(), p.getMoneda(), p.getCreadoEn(), p.getNombreContacto());
    }
}

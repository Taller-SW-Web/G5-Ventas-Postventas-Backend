package ventas.pedido.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import ventas.pedido.CanalVenta;

/**
 * Respuesta 200 de 1.14 KPIs de ventas (Dashboard, mockup 01).
 * montoVendido y ventasPorDia/ventasPorCanal suman pedido.total de los pedidos vendidos (PAGADO, EN_PREPARACION,
 * DESPACHADO o ENTREGADO). pedidosTotal no cuenta los que siguen en CREADO (sin pagar).
 */
public record KpisVentasResponse(
        LocalDate desde,
        LocalDate hasta,
        CanalVenta canal,
        BigDecimal montoVendido,
        long pedidosVendidos,
        BigDecimal ticketPromedio,
        long pedidosTotal,
        long pedidosEntregados,
        long pedidosAnulados,
        List<VentaDia> ventasPorDia,
        Map<CanalVenta, BigDecimal> ventasPorCanal) {

    public record VentaDia(LocalDate fecha, BigDecimal monto) {}
}

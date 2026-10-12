package ventas.pedido.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import ventas.pedido.CanalVenta;
import ventas.pedido.EstadoPedido;

/** Un pedido dentro de un listado (1.4 y 1.4b de api-ventas.md). */
public record PedidoResumen(
        String pedidoId,
        CanalVenta canal,
        EstadoPedido estado,
        BigDecimal total,
        String moneda,
        OffsetDateTime fechaCreacion) {}

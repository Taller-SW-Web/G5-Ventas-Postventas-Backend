package ventas.pedido.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import ventas.pedido.CanalVenta;
import ventas.pedido.EstadoPedido;

/** Respuesta de 1.1 (201, o 200 si es una repetición idempotente). */
public record CrearPedidoResponse(
        String pedidoId,
        EstadoPedido estado,
        BigDecimal total,
        String moneda,
        CanalVenta canal,
        OffsetDateTime fechaCreacion) {}

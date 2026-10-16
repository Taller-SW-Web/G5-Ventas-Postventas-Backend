package ventas.pedido.dto;

import java.time.OffsetDateTime;

import ventas.pedido.EstadoPedido;

/** Respuesta de 1.11 (201, o 200 si esa devolución ya tenía su reemplazo vigente). */
public record CrearReemplazoResponse(
        String pedidoId,
        EstadoPedido estado,
        String devolucionId,
        String pedidoOrigenId,
        OffsetDateTime fechaCreacion) {}

package ventas.pedido.dto;

import java.time.OffsetDateTime;

import ventas.pedido.EstadoPedido;

/** Respuesta 200 de 1.5. Si el pedido ya estaba en nuevoEstado (reintento), estadoAnterior == nuevoEstado. */
public record CambiarEstadoResponse(
        String pedidoId,
        EstadoPedido estadoAnterior,
        EstadoPedido nuevoEstado,
        OffsetDateTime fechaTransicion) {}

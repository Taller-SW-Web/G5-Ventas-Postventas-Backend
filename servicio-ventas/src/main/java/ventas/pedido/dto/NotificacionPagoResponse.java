package ventas.pedido.dto;

import java.time.OffsetDateTime;

import ventas.pedido.EstadoPedido;

/** Respuesta 200 de 1.2. Si la notificación es un reintento, devuelve lo mismo que la primera vez. */
public record NotificacionPagoResponse(
        String pedidoId,
        EstadoPedido nuevoEstado,
        String transaccionId,
        OffsetDateTime fechaTransicion) {}

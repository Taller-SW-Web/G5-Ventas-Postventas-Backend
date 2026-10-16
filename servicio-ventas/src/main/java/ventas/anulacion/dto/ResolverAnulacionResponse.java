package ventas.anulacion.dto;

/** Response de 1.9 PATCH /api/v1/pedidos/{pedidoId}/anulaciones/{anulacionId} */
public record ResolverAnulacionResponse(
        String pedidoId,
        String estadoPedido,
        String estadoAnulacion
) {
}

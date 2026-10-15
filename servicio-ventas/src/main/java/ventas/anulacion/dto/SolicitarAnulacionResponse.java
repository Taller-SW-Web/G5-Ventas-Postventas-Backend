package ventas.anulacion.dto;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Response de 1.7 POST /api/v1/pedidos/{pedidoId}/anulaciones */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SolicitarAnulacionResponse(
        String pedidoId,
        String estadoPedido,
        Boolean autorizacionRequerida,
        Boolean solicitudReembolsoGenerada,
        String solicitudAnulacionId,
        String mensaje,
        OffsetDateTime timestamp
) {
    public static SolicitarAnulacionResponse directa(String pedidoId, String estadoPedido, boolean reembolsoGenerado) {
        return new SolicitarAnulacionResponse(
                pedidoId,
                estadoPedido,
                false,
                reembolsoGenerado,
                null,
                null,
                OffsetDateTime.now(java.time.ZoneOffset.UTC)
        );
    }

    public static SolicitarAnulacionResponse pendiente(String pedidoId, String estadoPedido, String solicitudAnulacionId, String mensaje) {
        return new SolicitarAnulacionResponse(
                pedidoId,
                estadoPedido,
                true,
                null,
                solicitudAnulacionId,
                mensaje,
                null
        );
    }
}

package ventas.anulacion.dto;

import jakarta.validation.constraints.NotBlank;

/** Request de 1.7 POST /api/v1/pedidos/{pedidoId}/anulaciones */
public record SolicitarAnulacionRequest(
        @NotBlank(message = "El motivo es obligatorio")
        String motivo,
        String comentario
) {
}

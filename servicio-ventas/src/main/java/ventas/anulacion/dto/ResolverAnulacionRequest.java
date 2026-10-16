package ventas.anulacion.dto;

import jakarta.validation.constraints.NotBlank;

/** Request de 1.9 PATCH /api/v1/pedidos/{pedidoId}/anulaciones/{anulacionId} */
public record ResolverAnulacionRequest(
        @NotBlank(message = "La decisión es obligatoria (APROBADA o RECHAZADA)")
        String decision,

        String autorizadorId,

        @NotBlank(message = "El comentario o fundamento es obligatorio")
        String comentario
) {
}

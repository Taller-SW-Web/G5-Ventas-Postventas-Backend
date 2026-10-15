package pasarela.extorno.dto;

import java.time.OffsetDateTime;

/**
 * Respuesta de error del simulador de extornos.
 */
public record ExtornoError(
        String codigo,
        String mensaje,
        OffsetDateTime timestamp) {
}

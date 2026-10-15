package pasarela.cobro.dto;

import java.time.OffsetDateTime;

/**
 * Respuesta uniforme para los errores de la API de cobros.
 */
public record CobroError(
        String codigo,
        String mensaje,
        OffsetDateTime fecha) {
}

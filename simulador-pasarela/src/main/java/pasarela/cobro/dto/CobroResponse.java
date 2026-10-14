package pasarela.cobro.dto;

import pasarela.cobro.EstadoCobro;

/**
 * Respuesta inicial del cobro.
 * El resultado se procesa y notifica después.
 */
public record CobroResponse(
        String transaccionId,
        EstadoCobro estado) {
}

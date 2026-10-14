package pasarela.extorno.dto;

/**
 * Resultado de un intento de extorno.
 * estado contiene EXITOSO o FALLIDO.
 */
public record ExtornoResponse(
        String idTransaccion,
        String estado,
        String detalle) {
}

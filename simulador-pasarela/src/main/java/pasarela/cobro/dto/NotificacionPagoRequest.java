package pasarela.cobro.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import pasarela.cobro.EscenarioCobro;

/**
 * Resultado enviado al endpoint de notificación de pagos de M1.
 *
 * El contrato utiliza "metodo", aunque la solicitud de cobro
 * utiliza "metodoPago".
 */
public record NotificacionPagoRequest(
        String transaccionId,
        EscenarioCobro resultado,
        BigDecimal monto,
        String moneda,
        String metodo,

        // Mantiene el formato ISO 8601 en el JSON enviado a M1.
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        OffsetDateTime fechaPago) {
}

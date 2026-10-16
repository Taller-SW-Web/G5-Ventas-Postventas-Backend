package pasarela.cobro.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import pasarela.cobro.EstadoCobro;

/**
 * Estado consultable de una transacción y de su notificación a M1.
 */
public record ConsultaCobroResponse(
        String transaccionId,
        String pedidoId,
        BigDecimal monto,
        String moneda,
        String metodoPago,
        EstadoCobro estado,
        boolean notificadoM1,
        int intentosNotificacion,
        OffsetDateTime fechaCreacion,
        OffsetDateTime fechaPago) {
}

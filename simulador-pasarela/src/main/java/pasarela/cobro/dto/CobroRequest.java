package pasarela.cobro.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import pasarela.cobro.EscenarioCobro;

/**
 * Solicitud recibida desde M1 o una herramienta de pruebas.
 * Si escenario no viene, se utiliza APROBADO.
 */
public record CobroRequest(

        @NotBlank
        String pedidoId,

        @NotNull
        @Positive
        BigDecimal monto,

        @NotBlank
        @Pattern(regexp = "PEN|USD")
        String moneda,

        @NotBlank
        @Size(max = 30)
        String metodoPago,

        EscenarioCobro escenario) {
}

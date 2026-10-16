package pasarela.extorno.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import pasarela.extorno.EscenarioExtorno;

/**
 * Solicitud de POST /pasarela/v1/extornos.
 * Si escenario no viene, el servicio utilizará EXITOSO.
 */
public record ExtornoRequest(

        @NotBlank
        String referencia,

        @NotNull
        @Positive
        BigDecimal monto,

        @NotBlank
        @Pattern(regexp = "PEN|USD")
        String moneda,

        EscenarioExtorno escenario) {
}

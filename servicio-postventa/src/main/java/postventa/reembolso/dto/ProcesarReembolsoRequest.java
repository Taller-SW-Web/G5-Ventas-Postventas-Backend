package postventa.reembolso.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.*;
import postventa.reembolso.OrigenReembolso;

public record ProcesarReembolsoRequest(@NotNull OrigenReembolso origen, @NotBlank String origenId,
    @NotBlank String referenciaId, @NotNull @DecimalMin(value="0.01") BigDecimal monto,
    @NotBlank @Pattern(regexp="[A-Z]{3}") String moneda, String motivo, String solicitadoPor) {}

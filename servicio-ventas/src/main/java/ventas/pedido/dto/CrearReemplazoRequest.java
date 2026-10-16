package ventas.pedido.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Cuerpo de 1.11: M2 pide la orden de reemplazo al aprobar un CAMBIO (F3, acuerdo G6 #100). */
public record CrearReemplazoRequest(
        @NotBlank @Pattern(regexp = "DEV-\\d{4}-\\d{4,}") String devolucionId,
        @NotBlank @Pattern(regexp = "PED-\\d{4}-\\d{5,}") String pedidoOrigenId,
        @NotEmpty List<@NotNull @Valid Linea> items) {

    public record Linea(
            @NotBlank @Size(max = 50) String sku,
            @NotNull @Positive Integer cantidad) {}
}

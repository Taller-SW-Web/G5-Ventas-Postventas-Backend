package postventa.devolucion.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de 2.5b. sku es el de la línea del pedido original (items[].sku de la 1.3), nunca se infiere del
 * productoId. cantidadApta ≤ cantidadRecibida lo valida el service (400 CANTIDAD_INVALIDA). evidencia es opcional.
 */
public record ConfirmarRecepcionRequest(
        @NotEmpty @Valid List<Linea> lineas,
        @Size(max = 500) String evidencia) {

    public record Linea(@NotBlank @Size(max = 50) String sku,
                        @NotNull @Positive Integer cantidadRecibida,
                        @NotNull @PositiveOrZero Integer cantidadApta) {}
}

package postventa.devolucion.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import postventa.devolucion.MotivoDevolucion;
import postventa.devolucion.TipoDevolucion;
import postventa.devolucion.TipoEvidencia;

/** Cuerpo de 2.2. Un motivo o tipo fuera del catálogo lo rechaza Jackson (400 FORMATO_INVALIDO). */
public record RegistrarDevolucionRequest(
        @NotBlank String pedidoId,
        @NotNull TipoDevolucion tipo,
        @NotNull MotivoDevolucion motivo,
        @Size(max = 2000) String descripcion,
        @NotEmpty @Valid List<Item> items,
        @Valid List<Evidencia> evidencias) {

    public record Item(@NotBlank String productoId, @NotNull @Positive Integer cantidad) {}

    public record Evidencia(@NotNull TipoEvidencia tipo, @NotBlank @Size(max = 500) String url) {}

    public List<Evidencia> evidenciasOVacio() {
        return evidencias == null ? List.of() : evidencias;
    }
}

package postventa.calificacion.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import postventa.compartido.CanalVenta;

/**
 * Datos de entrada de POST /api/v2/csat.
 * La identidad del cliente se verifica mediante el JWT.
 */
public record RegistrarCsatRequest(

        @NotBlank
        String pedidoId,

        @NotNull
        UUID clienteId,

        @NotNull
        CanalVenta canal,

        @NotNull
        @Min(1)
        @Max(5)
        Integer puntuacion,

        String comentario) {
}

package postventa.reclamo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import postventa.compartido.CanalVenta;
import postventa.reclamo.MotivoReclamo;
import postventa.reclamo.TipoReclamo;

/**
 * Cuerpo de 2.8. pedidoId es opcional (queja general, ESP-13 CA-02). Los tamaños máximos son los de la tabla:
 * evitan un 500 de la base, no agregan reglas de formato.
 */
public record RegistrarReclamoRequest(
        String pedidoId,
        @NotNull TipoReclamo tipo,
        @NotNull CanalVenta canal,
        @NotNull MotivoReclamo motivo,
        @NotBlank String detalle,
        @NotNull @Valid Consumidor consumidor) {

    public record Consumidor(
            @NotBlank @Size(max = 150) String nombreCompleto,
            @NotBlank @Size(max = 20) String documento,
            @NotBlank @Size(max = 150) String email,
            @NotBlank @Size(max = 20) String telefono) {}
}

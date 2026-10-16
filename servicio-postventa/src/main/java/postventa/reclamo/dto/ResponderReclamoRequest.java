package postventa.reclamo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import postventa.reclamo.EstadoReclamo;

/**
 * Cuerpo de 2.11. nuevoEstado: EN_PROCESO ("Iniciar atención", sin respuesta), ATENDIDO o DERIVADO (con
 * respuestaVisibleCliente). La respuesta vacía la revisa el service para responder RESPUESTA_VACIA.
 */
public record ResponderReclamoRequest(
        @NotNull EstadoReclamo nuevoEstado,
        String respuestaVisibleCliente,
        @NotBlank @Size(max = 50) String atendidoPor) {}

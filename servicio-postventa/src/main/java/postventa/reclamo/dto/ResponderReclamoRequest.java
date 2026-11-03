package postventa.reclamo.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import postventa.reclamo.EstadoReclamo;

/**
 * Cuerpo de 2.11. nuevoEstado: EN_PROCESO ("Iniciar atención", sin respuesta), ATENDIDO o DERIVADO (con
 * respuestaVisibleCliente). La respuesta vacía la revisa el service para responder RESPUESTA_VACIA.
 * atendidoPor queda por compatibilidad: con seguridad el autor es el sub del token del Gestor y este valor se
 * ignora; solo con la seguridad apagada (desarrollo local) se usa y entonces es obligatorio (ReclamoService).
 */
public record ResponderReclamoRequest(
        @NotNull EstadoReclamo nuevoEstado,
        String respuestaVisibleCliente,
        @Size(max = 50) String atendidoPor) {}

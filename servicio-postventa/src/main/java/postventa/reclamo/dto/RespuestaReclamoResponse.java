package postventa.reclamo.dto;

import java.time.OffsetDateTime;

import postventa.reclamo.EstadoReclamo;

/** Respuesta 200 de 2.11. fechaRespuesta es null al pasar a EN_PROCESO (todavía no hay respuesta formal). */
public record RespuestaReclamoResponse(String reclamoId, EstadoReclamo estado, OffsetDateTime fechaRespuesta,
                                       String respuestaVisibleCliente) {}

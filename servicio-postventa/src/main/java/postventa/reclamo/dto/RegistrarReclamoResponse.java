package postventa.reclamo.dto;

import java.time.OffsetDateTime;

import postventa.reclamo.EstadoReclamo;
import postventa.reclamo.MotivoReclamo;

/** Respuesta 201 de 2.8. respuestaVisibleCliente siempre es null al registrar. */
public record RegistrarReclamoResponse(String reclamoId, String codigoSeguimiento, EstadoReclamo estado,
                                       MotivoReclamo motivo, int plazoDiasHabiles, OffsetDateTime fechaLimiteSLA,
                                       String respuestaVisibleCliente) {}

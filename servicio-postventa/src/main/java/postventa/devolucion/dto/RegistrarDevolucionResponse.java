package postventa.devolucion.dto;

import java.time.OffsetDateTime;

import postventa.devolucion.EstadoDevolucion;
import postventa.devolucion.TipoDevolucion;

/** Respuesta 201 de 2.2. */
public record RegistrarDevolucionResponse(String devolucionId, String pedidoId, TipoDevolucion tipo,
                                          EstadoDevolucion estado, OffsetDateTime fechaRegistro) {}

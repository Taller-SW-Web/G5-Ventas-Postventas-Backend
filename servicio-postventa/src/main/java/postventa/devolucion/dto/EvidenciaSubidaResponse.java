package postventa.devolucion.dto;

import java.time.OffsetDateTime;

import postventa.devolucion.TipoEvidencia;

/** Respuesta 201 de 2.1. La url es la que va luego en evidencias de 2.2. */
public record EvidenciaSubidaResponse(TipoEvidencia tipo, String url, String nombreArchivoOriginal,
                                      long tamanioBytes, OffsetDateTime fechaSubida) {}

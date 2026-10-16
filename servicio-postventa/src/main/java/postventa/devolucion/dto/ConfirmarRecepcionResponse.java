package postventa.devolucion.dto;

import java.time.OffsetDateTime;
import java.util.List;

import postventa.devolucion.EstadoDevolucion;

/**
 * Respuesta 200 de 2.5b. lineas trae solo lo reintegrado (sku y cantidadApta). reintegroSolicitado es false si
 * ninguna unidad era apta. Agrega estado al contrato: pasa a COMPLETADA si con esta recepción ya no falta nada.
 */
public record ConfirmarRecepcionResponse(String devolucionId, boolean recepcionConfirmada, OffsetDateTime fechaRecepcion,
                                         String actor, List<Linea> lineas, boolean reintegroSolicitado,
                                         EstadoDevolucion estado) {

    public record Linea(String sku, int cantidadApta) {}
}

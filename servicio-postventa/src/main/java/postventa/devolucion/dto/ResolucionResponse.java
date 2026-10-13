package postventa.devolucion.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import postventa.devolucion.DecisionDevolucion;
import postventa.devolucion.EstadoDevolucion;
import postventa.devolucion.TipoResolucion;

/**
 * Respuesta 200 de 2.5 (campos de salida de ESP-08, 8.3). estado es APROBADA mientras F4 o Despacho no
 * confirman; COMPLETADA cuando ya confirmaron. referenciaOperacion es el reembolsoId o la referencia de Despacho.
 */
public record ResolucionResponse(String devolucionId, EstadoDevolucion estado, DecisionDevolucion decision,
                                 TipoResolucion tipoResolucion, String fundamento, UUID autorizadorId,
                                 OffsetDateTime fechaResolucion, String referenciaOperacion) {}

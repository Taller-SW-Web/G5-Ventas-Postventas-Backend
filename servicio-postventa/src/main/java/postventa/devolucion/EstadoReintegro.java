package postventa.devolucion;

/**
 * Estado del reintegro de una recepción física. PENDIENTE si Productos no respondió: se reenvía con el mismo
 * operation_id en la siguiente confirmación. COMPLETADO si Productos lo aplicó o si no había unidades aptas.
 */
public enum EstadoReintegro {
    PENDIENTE, COMPLETADO
}

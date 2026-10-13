package postventa.devolucion;

/** SOLICITADA -> EN_EVALUACION -> APROBADA | RECHAZADA; APROBADA -> COMPLETADA (ESP-08, 11.1). */
public enum EstadoDevolucion {
    SOLICITADA, EN_EVALUACION, APROBADA, RECHAZADA, COMPLETADA;

    /** Solo se dictamina un expediente que todavía no tiene resolución. */
    public boolean resoluble() {
        return this == SOLICITADA || this == EN_EVALUACION;
    }
}

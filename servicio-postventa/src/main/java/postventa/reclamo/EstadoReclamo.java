package postventa.reclamo;

/** Ciclo de vida del reclamo: REGISTRADO -> EN_PROCESO -> ATENDIDO, con la rama DERIVADO (F6 4.1). */
public enum EstadoReclamo {
    REGISTRADO, EN_PROCESO, ATENDIDO, DERIVADO
}

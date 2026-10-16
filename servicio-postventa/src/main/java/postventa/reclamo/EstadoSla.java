package postventa.reclamo;

/**
 * Semáforo del plazo legal, distinto del estado de atención (propuesta UX 01): un reclamo puede estar
 * EN_PROCESO y VENCIDO a la vez. Rangos en días hábiles restantes hasta fechaLimiteSLA.
 */
public enum EstadoSla {
    /** 6 o más días hábiles. */
    EN_PLAZO,
    /** 3 a 5 días hábiles. */
    POR_VENCER,
    /** 0 a 2 días hábiles (0 = vence hoy). */
    CRITICO,
    /** Pasó la fecha límite sin respuesta. */
    VENCIDO,
    /** Ya tiene respuesta formal (ATENDIDO). */
    ATENDIDO
}

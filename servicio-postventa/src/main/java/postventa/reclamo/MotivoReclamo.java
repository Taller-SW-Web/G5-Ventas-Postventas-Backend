package postventa.reclamo;

/** Catálogo cerrado de motivos (F6 4.2, ESP-13 3.3). Un valor fuera de la lista responde 400 FORMATO_INVALIDO. */
public enum MotivoReclamo {
    INCUMPLIMIENTO_PLAZO_ENTREGA,
    PRODUCTO_DEFECTUOSO_O_INCORRECTO,
    COBRO_INDEBIDO_O_NO_RECONOCIDO,
    ATENCION_INADECUADA,
    INCUMPLIMIENTO_GARANTIA
}

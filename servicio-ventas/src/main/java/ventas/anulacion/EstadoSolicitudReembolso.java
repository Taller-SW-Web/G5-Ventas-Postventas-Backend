package ventas.anulacion;

/** Valores del CHECK de ventas.solicitud_reembolso.estado. */
public enum EstadoSolicitudReembolso {
    /** Falta que M2 la acepte: el reintento la vuelve a mandar. */
    PENDIENTE,
    /** M2 la aceptó (201). */
    ENVIADA,
    /** M2 respondió 400/409: reintentar no sirve, la revisa una persona. */
    RECHAZADA
}

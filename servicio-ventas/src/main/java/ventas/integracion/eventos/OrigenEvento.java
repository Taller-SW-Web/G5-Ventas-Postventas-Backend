package ventas.integracion.eventos;

/** Sistema externo que envió una notificación a M1. */
public enum OrigenEvento {
    PASARELA_PAGOS,      // 1.2 notificación de pago
    SISTEMA_LOGISTICA    // 1.6 eventos de logística
}

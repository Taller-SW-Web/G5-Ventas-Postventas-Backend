package postventa.devolucion;

/** Quién hizo un cambio en el expediente (CHECK de postventa.historial_devolucion.actor). */
public enum ActorDevolucion {
    /** El cliente con su propio token (id_actor = su sub). */
    CLIENTE,
    /** Un canal con token de servicio (id_actor = su módulo, por ejemplo modulo-marketplace). */
    CANAL,
    /** El Gestor, rol ADMIN_VENTAS (id_actor = su sub). */
    GESTOR,
    /** M2 mismo, cuando F4 o Despacho confirman la operación. */
    SISTEMA
}

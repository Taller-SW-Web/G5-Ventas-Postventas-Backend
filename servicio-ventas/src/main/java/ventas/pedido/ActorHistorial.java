package ventas.pedido;

/** Quién provoca un cambio de estado (matriz de ESP-03). */
public enum ActorHistorial {
    CLIENTE, CANAL_CHATBOT, PASARELA_PAGOS, SISTEMA_LOGISTICA, GESTOR,
    /** Productos (G6) rechazó la reserva de stock. Provisional: lo debe aprobar el equipo (ESP-03) */
    SISTEMA_PRODUCTOS
}

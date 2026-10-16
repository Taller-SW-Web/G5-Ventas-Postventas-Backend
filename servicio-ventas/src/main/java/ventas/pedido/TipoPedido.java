package ventas.pedido;

/**
 * VENTA: el pedido normal de 1.1. REEMPLAZO: la orden sin cobro que M2 pide al aprobar un CAMBIO (F3), con su propia
 * reserva y su propio despacho (acuerdo con G6, issue #100). Va vinculada al pedido original y a la devolución.
 */
public enum TipoPedido {
    VENTA, REEMPLAZO
}

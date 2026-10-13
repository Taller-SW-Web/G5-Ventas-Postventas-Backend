package ventas.pedido;

/** Los 7 eventos del webhook de Despacho (acuerdo G2, D2 a D4). */
public enum TipoEventoDespacho {
    DESPACHO_ASIGNADO, DESPACHO_EN_CAMINO, DESPACHO_ENTREGADO, DESPACHO_FALLIDO,
    DESPACHO_DEVUELTO_A_ORIGEN, DESPACHO_REPROGRAMADO, DESPACHO_CANCELADO
}

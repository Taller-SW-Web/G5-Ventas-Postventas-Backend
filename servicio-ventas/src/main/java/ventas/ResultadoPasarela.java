package ventas.pedido;

/** Resultado que informa la pasarela en la notificación de pago (1.2). */
public enum ResultadoPasarela {
    APROBADO, RECHAZADO;

    public EstadoPago aEstadoPago() {
        return this == APROBADO ? EstadoPago.APROBADO : EstadoPago.RECHAZADO;
    }
}

package postventa.calificacion;

/**
 * El pedido todavía no cumple la condición para recibir una encuesta CSAT.
 */
public class PedidoNoEntregadoException extends RuntimeException {

    public PedidoNoEntregadoException() {
        super("Solo se pueden calificar pedidos entregados");
    }
}

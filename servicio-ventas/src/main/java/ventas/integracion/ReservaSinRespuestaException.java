package ventas.integracion;

import ventas.compartido.DependenciaNoDisponibleException;

/**
 * El POST de la reserva terminó en timeout, en 5xx o sin respuesta (contrato H5 D-07): Productos pudo haberla
 * admitido y persistido. El pedido no debe perderse: se guarda igual y SeguimientoProductos consulta el resultado o
 * reenvía la reserva con la misma identidad y contenido.
 */
public class ReservaSinRespuestaException extends DependenciaNoDisponibleException {

    public ReservaSinRespuestaException(String mensaje, Throwable causa) {
        super("PRODUCTOS_NO_DISPONIBLE", mensaje, causa);
    }
}

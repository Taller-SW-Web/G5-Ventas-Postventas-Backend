package postventa.integracion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import postventa.compartido.CanalVenta;

/**
 * Lo que M2 necesita de un pedido de M1 (1.3). M2 nunca lee las tablas de M1 (RNF-07).
 * fechaEntrega es la fecha del historial ENTREGADO (null si el pedido no se entregó).
 * canal es null si M1 no lo manda o trae un valor desconocido (VentasClient); los constructores cortos, que usan
 * pruebas y adaptadores anteriores al campo, asumen MARKETPLACE.
 */
public record PedidoResumen(String pedidoId, String estado, UUID clienteId, BigDecimal total, String moneda,
                            List<Item> items, OffsetDateTime fechaEntrega, boolean pagoConfirmado, CanalVenta canal) {

    public static final String ENTREGADO = "ENTREGADO";

    /** Constructor conservado para pruebas y adaptadores que ya entregan un pedido pagado. */
    public PedidoResumen(String pedidoId, String estado, UUID clienteId, BigDecimal total, String moneda,
                         List<Item> items, OffsetDateTime fechaEntrega) {
        this(pedidoId, estado, clienteId, total, moneda, items, fechaEntrega, true, CanalVenta.MARKETPLACE);
    }

    /** Compatibilidad para adaptadores anteriores al campo canal de la API 1.3. */
    public PedidoResumen(String pedidoId, String estado, UUID clienteId, BigDecimal total, String moneda,
                         List<Item> items, OffsetDateTime fechaEntrega, boolean pagoConfirmado) {
        this(pedidoId, estado, clienteId, total, moneda, items, fechaEntrega, pagoConfirmado,
                CanalVenta.MARKETPLACE);
    }

    /**
     * productoId es texto, tal cual lo da Productos (UUID o código como prd-013-01) y lo devuelve M1 en la 1.3.
     * sku es el de la línea del pedido: la recepción física (2.5b) y el reintegro a Productos van por sku.
     */
    public record Item(String productoId, int cantidad, BigDecimal precioUnitario, String sku) {

        /** Sin sku (lo usan las pruebas que no lo necesitan). */
        public Item(String productoId, int cantidad, BigDecimal precioUnitario) {
            this(productoId, cantidad, precioUnitario, null);
        }
    }

    public boolean entregado() {
        return ENTREGADO.equals(estado);
    }
}

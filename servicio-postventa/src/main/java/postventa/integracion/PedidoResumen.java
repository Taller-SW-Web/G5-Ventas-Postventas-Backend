package postventa.integracion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lo que M2 necesita de un pedido de M1 (1.3). M2 nunca lee las tablas de M1 (RNF-07).
 * fechaEntrega es la fecha del historial ENTREGADO (null si el pedido no se entregó).
 */
public record PedidoResumen(String pedidoId, String estado, UUID clienteId, BigDecimal total, String moneda,
                            List<Item> items, OffsetDateTime fechaEntrega) {

    public static final String ENTREGADO = "ENTREGADO";

    /** productoId es texto, tal cual lo da Productos (UUID o código como prd-013-01) y lo devuelve M1 en la 1.3. */
    public record Item(String productoId, int cantidad, BigDecimal precioUnitario) {}

    public boolean entregado() {
        return ENTREGADO.equals(estado);
    }
}

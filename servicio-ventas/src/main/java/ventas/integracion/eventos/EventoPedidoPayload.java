package ventas.integracion.eventos;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record EventoPedidoPayload(
        UUID idEvento,
        String tipo,
        String pedidoId,
        OffsetDateTime fecha,
        Datos datos) {

    public record Datos(
            String canal,
            String clienteId,
            UUID vendedorId,
            BigDecimal total,
            String moneda,
            String motivo,          // obligatorio en pedido.anulado
            List<Item> items,       // solo en pedido.pagado
            String devolucionId,    // solo en órdenes de reemplazo (F3): M2 cierra o reabre esa devolución
            CambioTienda cambioTienda) { // solo pedido.entregado de un cambio en tienda: M2 reintegra lo devuelto

        public Datos(String canal, String clienteId, UUID vendedorId, BigDecimal total, String moneda, String motivo,
                     List<Item> items, String devolucionId) {
            this(canal, clienteId, vendedorId, total, moneda, motivo, items, devolucionId, null);
        }
    }

    /** Cambio de prenda en tienda (acuerdo G4, 26/11): pedido de la prenda devuelta y lo que se devuelve. */
    public record CambioTienda(String pedidoOrigenId, List<ItemDevuelto> itemsDevueltos) {}

    public record ItemDevuelto(String sku, Integer cantidad) {}

    public record Item(String productoId, Integer cantidad, BigDecimal importe) {}
}

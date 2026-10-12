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
            List<Item> items) {}    // solo en pedido.pagado

    public record Item(Long productoId, Integer cantidad, BigDecimal importe) {}
}

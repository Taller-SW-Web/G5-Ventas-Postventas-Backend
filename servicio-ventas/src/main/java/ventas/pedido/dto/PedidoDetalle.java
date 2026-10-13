package ventas.pedido.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ventas.pedido.ActorHistorial;
import ventas.pedido.CanalVenta;
import ventas.pedido.EstadoPedido;

/** Respuesta 200 de 1.3 Consultar pedido (api-ventas.md 1.3). */
public record PedidoDetalle(
        String pedidoId,
        CanalVenta canal,
        EstadoPedido estado,
        Contacto contacto,
        Pago pago,
        Envio envio,
        List<Item> items,
        OffsetDateTime fechaCreacion,
        OffsetDateTime ultimaActualizacion,
        List<Historial> historialEstados) {

    public record Contacto(UUID clienteId, String nombreCompleto, String tipoDocumento, String numeroDocumento,
                           String email, String telefono) {}

    public record Pago(BigDecimal total, String moneda, String metodoPago) {}

    /** tiendaId solo en RETAIL (acuerdo G4); en los demás canales es null. */
    public record Envio(String modalidad, String direccion, String distrito, String tiendaId) {}

    public record Item(String productoId, String sku, String descripcion, Integer cantidad, BigDecimal precioUnitario) {}

    public record Historial(EstadoPedido estado, OffsetDateTime fecha, ActorHistorial actor, String motivo) {}
}

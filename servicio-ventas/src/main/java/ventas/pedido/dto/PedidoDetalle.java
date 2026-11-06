package ventas.pedido.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ventas.pedido.ActorHistorial;
import ventas.pedido.CanalVenta;
import ventas.pedido.EstadoPago;
import ventas.pedido.EstadoPedido;
import ventas.pedido.TipoPedido;

/**
 * Respuesta 200 de 1.3 Consultar pedido (api-ventas.md 1.3).
 * tipo (agregado 2026-11-04, aditivo): VENTA o REEMPLAZO (ventas.pedido.tipo). Un REEMPLAZO es la orden sin cobro de
 * un CAMBIO (F3, 1.11): nunca pasa por PAGADO.
 */
public record PedidoDetalle(
        String pedidoId,
        CanalVenta canal,
        EstadoPedido estado,
        TipoPedido tipo,
        Contacto contacto,
        Pago pago,
        Envio envio,
        List<Item> items,
        OffsetDateTime fechaCreacion,
        OffsetDateTime ultimaActualizacion,
        List<Historial> historialEstados) {

    public record Contacto(UUID clienteId, String nombreCompleto, String tipoDocumento, String numeroDocumento,
                           String email, String telefono) {}

    /**
     * estado (agregado 2026-11-04): estado real del cobro en ventas.pago (PENDIENTE, CONFIRMADO o RECHAZADO; null si
     * el pedido no tiene fila de pago). Un pedido ANULADO puede tener el cobro CONFIRMADO si la pasarela lo confirmó
     * después de la anulación (1.2): M2 lo usa para aceptar el reembolso sin deducirlo del historial.
     */
    public record Pago(BigDecimal total, String moneda, String metodoPago, EstadoPago estado) {}

    /** tiendaId solo en RETAIL (acuerdo G4). fechaEstimadaEntrega la fija Despacho al reprogramar (G2 D3). */
    public record Envio(String modalidad, String direccion, String distrito, String tiendaId,
                        String departamento, String provincia, String referencia, OffsetDateTime fechaEstimadaEntrega) {}

    public record Item(String productoId, String sku, String descripcion, Integer cantidad, BigDecimal precioUnitario) {}

    public record Historial(EstadoPedido estado, OffsetDateTime fecha, ActorHistorial actor, String motivo) {}
}

package postventa.integracion.eventos;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import postventa.compartido.CanalVenta;

/**
 * Evento de pedido recibido desde M1.
 *
 * Mantiene los nombres del contrato publicado por Ventas sin depender
 * de sus clases Java.
 */
public record EventoPedidoPayload(

        @NotNull
        UUID idEvento,

        @NotBlank
        @Pattern(regexp = "pedido\\.(pagado|anulado|entregado)")
        String tipo,

        @NotBlank
        String pedidoId,

        @NotNull
        OffsetDateTime fecha,

        @NotNull
        @Valid
        Datos datos) {

    /**
     * Vendedor e ítems pueden omitirse en eventos de anulación y entrega.
     * Las condiciones específicas de cada tipo se validarán en el servicio.
     */
    public record Datos(

            @NotNull
            CanalVenta canal,

            @NotBlank
            String clienteId,

            UUID vendedorId,

            @NotNull
            @PositiveOrZero
            BigDecimal total,

            @NotBlank
            @Pattern(regexp = "PEN|USD")
            String moneda,

            String motivo,

            @Valid
            List<@NotNull Item> items,

            // Solo en órdenes de reemplazo de un CAMBIO (F3, api-ventas 1.11)
            @Pattern(regexp = "DEV-\\d{4}-\\d{4,}")
            String devolucionId) {
    }

    public record Item(

            @NotBlank
            String productoId,

            @NotNull
            @Positive
            Integer cantidad,

            @NotNull
            @PositiveOrZero
            BigDecimal importe) {
    }
}

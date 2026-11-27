package ventas.pedido.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Evidencia de cupón para Promotions (G6): foto comercial del pedido que Ventas guardó al crearlo.
 * Nombres y formato del contrato de G6 (CONTRATO_EVIDENCIA_CUPON_G5.md, g5-coupon-snapshot.schema.json):
 * snake_case, precios como texto decimal y sin nombres, documentos ni banderas de elegibilidad.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EvidenciaCuponResponse(
        @JsonProperty("order_id") String orderId,
        @JsonProperty("commercial_snapshot_ref") String commercialSnapshotRef,
        @JsonProperty("reservation_id") String reservationId,
        @JsonProperty("effective_at") OffsetDateTime effectiveAt,
        @JsonProperty("customer_ref") String customerRef,
        @JsonProperty("channel_id") String channelId,
        @JsonProperty("coupon_code") String couponCode,
        @JsonProperty("lines") List<Linea> lines) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Linea(
            @JsonProperty("sku") String sku,
            @JsonProperty("product_id") String productId,
            @JsonProperty("quantity") long quantity,
            @JsonProperty("currency") String currency,
            @JsonProperty("regular_price") String regularPrice,
            @JsonProperty("offer_price") String offerPrice) {}
}

package ventas.pedido;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ventas.compartido.RecursoNoEncontradoException;
import ventas.pedido.dto.EvidenciaCuponResponse;

/**
 * Evidencia de cupón para Promotions (G6, contrato H5 CONTRATO_EVIDENCIA_CUPON_G5.md).
 * Devuelve lo que Ventas guardó al crear el pedido (cliente, canal, fecha, cupón, líneas y precios) junto con la
 * reserva de Productos. Es de solo lectura e idempotente: mismas lecturas, mismos datos.
 * 404 si el pedido no existe, no tiene cupón o todavía no tiene una reserva real (UUID) de Productos:
 * G6 deja el consumo pendiente y no descuenta el cupón.
 */
@Service
@RequiredArgsConstructor
public class EvidenciaCuponService {

    static final String SIN_EVIDENCIA = "EVIDENCIA_CUPON_NO_DISPONIBLE";

    private final PedidoRepository pedidos;

    @Transactional(readOnly = true)
    public EvidenciaCuponResponse evidencia(String orderId) {
        Pedido p = pedidos.findByCodigo(orderId)
                .orElseThrow(() -> new RecursoNoEncontradoException(SIN_EVIDENCIA, "No hay evidencia de cupón para " + orderId));
        if (p.getCodigoCupon() == null || p.getCodigoCupon().isBlank()) {
            throw new RecursoNoEncontradoException(SIN_EVIDENCIA, "El pedido " + orderId + " no tiene cupón");
        }
        String reserva = reservaUuid(p.getReservaId());
        if (reserva == null) {
            throw new RecursoNoEncontradoException(SIN_EVIDENCIA, "El pedido " + orderId + " todavía no tiene reserva en Productos");
        }
        String moneda = p.getMoneda().trim().toUpperCase();
        // Una línea por SKU (el contrato no admite SKU repetidos): se suman las cantidades del mismo precio
        Map<String, EvidenciaCuponResponse.Linea> lineas = new LinkedHashMap<>();
        for (DetallePedido d : p.getDetalles()) {
            lineas.merge(d.getSku(), linea(d, moneda), (a, b) -> new EvidenciaCuponResponse.Linea(
                    a.sku(), a.productId(), a.quantity() + b.quantity(), moneda, a.regularPrice(), null));
        }
        return new EvidenciaCuponResponse(p.getCodigo(), p.getCodigo(), reserva,
                p.getCreadoEn().withOffsetSameInstant(ZoneOffset.UTC),
                p.getIdCliente() == null ? null : p.getIdCliente().toString(),
                p.getCanal().name(), p.getCodigoCupon().trim(), List.copyOf(lineas.values()));
    }

    /** Ventas guarda el precio unitario cobrado: va como regular_price y no hay precio de oferta aparte. */
    private static EvidenciaCuponResponse.Linea linea(DetallePedido d, String moneda) {
        return new EvidenciaCuponResponse.Linea(d.getSku(), d.getIdProducto(), d.getCantidad(), moneda,
                precio(d.getPrecioUnitario()), null);
    }

    /** Texto decimal sin exponente ni separador de miles, con hasta 6 decimales (patrón del contrato). */
    static String precio(BigDecimal v) {
        BigDecimal r = v.setScale(Math.min(Math.max(v.scale(), 0), 6), RoundingMode.HALF_UP).stripTrailingZeros();
        if (r.scale() < 0) {
            r = r.setScale(0);
        }
        String s = r.toPlainString();
        return s.startsWith(".") ? "0" + s : s;
    }

    /** Solo una reserva real de Productos (UUID); las simuladas (SIM-…) no son evidencia válida. */
    static String reservaUuid(String reservaId) {
        if (reservaId == null) {
            return null;
        }
        try {
            return UUID.fromString(reservaId.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

package postventa.integracion;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import postventa.compartido.DependenciaNoDisponibleException;

/**
 * Módulo F (Productos, G6) para F3:
 * - Disponibilidad para un cambio (ESP-08 flujo B). La ruta todavía no está acordada con Productos:
 *   en modo real responde 503 hasta que se acuerde.
 * - Reintegro de la unidad devuelta: POST /api/v1/inventario/reintegros (ACUERDO_G6_PRODUCTOS, 2026-10-11),
 *   al aprobar un CAMBIO o un REEMBOLSO. Admite reintegros parciales por línea. El cuerpo aún no está
 *   confirmado; se manda la referencia de la devolución para que Productos descarte repetidos.
 * Con integracion.simulado=true no se llama a nadie; el producto 999 (PROD-999) no tiene stock.
 */
@Slf4j
@Component
public class ProductosClient {

    public static final long PRODUCTO_SIN_STOCK_SIMULADO = 999;

    public record ItemProducto(long productoId, int cantidad) {}

    private record ReintegroRequest(String referencia, List<ItemProducto> items) {}

    private final boolean simulado;
    private final RestClient http;

    public ProductosClient(@Value("${integracion.simulado:true}") boolean simulado,
                           @Value("${integracion.productos-url:http://localhost:8090}") String url) {
        this.simulado = simulado;
        this.http = RestClient.builder().baseUrl(url).build();
    }

    /** true si todos los ítems tienen stock para enviarlos de nuevo. */
    public boolean hayDisponibilidad(List<ItemProducto> items) {
        if (simulado) {
            boolean hay = items.stream().noneMatch(i -> i.productoId() == PRODUCTO_SIN_STOCK_SIMULADO);
            log.info("[simulado] disponibilidad de {} -> {}", items, hay);
            return hay;
        }
        // Pendiente: ruta de consulta de disponibilidad con Productos (no está en ACUERDO_G6_PRODUCTOS)
        throw new DependenciaNoDisponibleException("PRODUCTOS_NO_DISPONIBLE",
                "La consulta de disponibilidad con Productos todavía no está habilitada");
    }

    /** Devuelve al inventario las unidades recibidas. Nunca lanza excepción: si falla, queda en el log. */
    public void reintegrar(String devolucionId, List<ItemProducto> items) {
        if (simulado) {
            log.info("[simulado] reintegro de {} por la devolución {}", items, devolucionId);
            return;
        }
        try {
            http.post().uri("/api/v1/inventario/reintegros")
                    .body(new ReintegroRequest(devolucionId, items))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("No se pudo reintegrar el stock de la devolución {} en Productos: {}", devolucionId, e.getMessage());
        }
    }
}

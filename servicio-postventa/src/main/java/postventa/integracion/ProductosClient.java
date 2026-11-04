package postventa.integracion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import postventa.compartido.DependenciaNoDisponibleException;

/**
 * Módulo F (Productos, G6) para F3:
 * - Disponibilidad para un cambio (ESP-08 flujo B): GET /api/v1/inventario/disponibilidad?skus=... (acordado con
 *   G6 el 2026-10-16, issue #100; scope inventario:disponibilidad:leer). Informativa, no reserva nada: hay stock si
 *   el available de cada sku cubre las unidades pedidas. Sin location_id (un solo almacén). Si no responde, 503.
 * - Reintegro de las unidades devueltas: POST /api/v1/inventario/reintegros (ACUERDO_G6_PRODUCTOS, 2026-10-13).
 *   Solo después de confirmar la recepción física (2.5b, issue G6 #51 P-02) y solo por las unidades aptas.
 *   Cuerpo confirmado contra su OpenAPI: operation_id, order_id, source_ref, reason DEVOLUCION_ACEPTADA y
 *   lines[] con sku, quantity y location_id. Un reintento usa el mismo operation_id (P-05). Token de servicio
 *   de M2 con scope inventario:reintegrar (pendiente de registrar en Seguridad).
 *   location_id todavía no lo define G6 (P-04): en modo real, sin integracion.productos-ubicacion-devoluciones
 *   responde 503 en vez de inventar un valor.
 * Con integracion.simulado=true no se llama a nadie; el producto PROD-999 no tiene stock.
 */
@Slf4j
@Component
public class ProductosClient {

    public static final String PRODUCTO_SIN_STOCK_SIMULADO = "PROD-999";

    /** Un producto del cambio: su productoId, el sku con el que lo conoce Productos y cuántas unidades se piden. */
    public record ItemProducto(String productoId, String sku, int cantidad) {}

    /** Respuesta de /inventario/disponibilidad: solo se usan sku y available. */
    record DisponibilidadResponse(List<DisponibilidadItem> items) {}

    record DisponibilidadItem(String sku, Integer available) {}

    /** Una línea a reintegrar: el sku del pedido y las unidades aptas. */
    public record LineaReintegro(String sku, int cantidad) {}

    static final String MOTIVO_DEVOLUCION = "DEVOLUCION_ACEPTADA";

    record ReintegroRequest(@JsonProperty("operation_id") UUID operationId,
                            @JsonProperty("order_id") String orderId,
                            @JsonProperty("source_ref") String sourceRef,
                            String reason,
                            List<LineaRequest> lines) {}

    record LineaRequest(String sku, int quantity, @JsonProperty("location_id") String locationId) {}

    private final boolean simulado;
    private final String ubicacion;
    private final TokenServicio tokenServicio;
    private final RestClient http;

    @Autowired
    public ProductosClient(@Value("${integracion.simulado:true}") boolean simulado,
                           @Value("${integracion.productos-url:http://localhost:8090}") String url,
                           @Value("${integracion.productos-ubicacion-devoluciones:}") String ubicacion,
                           TokenServicio tokenServicio) {
        this(simulado, ubicacion, tokenServicio, RestClient.builder().baseUrl(url));
    }

    ProductosClient(boolean simulado, String ubicacion, TokenServicio tokenServicio, RestClient.Builder http) {
        this.simulado = simulado;
        this.ubicacion = ubicacion == null ? "" : ubicacion.trim();
        this.tokenServicio = tokenServicio;
        this.http = http.build();
    }

    /** true si todos los ítems tienen stock para enviarlos de nuevo. */
    public boolean hayDisponibilidad(List<ItemProducto> items) {
        if (simulado) {
            boolean hay = items.stream().noneMatch(i -> PRODUCTO_SIN_STOCK_SIMULADO.equals(i.productoId()));
            log.info("[simulado] disponibilidad de {} -> {}", items, hay);
            return hay;
        }
        Map<String, Integer> pedidas = new LinkedHashMap<>();
        items.forEach(i -> pedidas.merge(i.sku(), i.cantidad(), Integer::sum));
        DisponibilidadResponse r;
        try {
            r = http.get().uri(u -> u.path("/api/v1/inventario/disponibilidad")
                            .queryParam("skus", String.join(",", pedidas.keySet())).build())
                    .headers(h -> tokenServicio.authorization().ifPresent(t -> h.set(HttpHeaders.AUTHORIZATION, t)))
                    .retrieve()
                    .body(DisponibilidadResponse.class);
        } catch (RestClientException e) {
            throw new DependenciaNoDisponibleException("PRODUCTOS_NO_DISPONIBLE",
                    "Productos no respondió la consulta de disponibilidad; intente nuevamente", e);
        }
        Map<String, Integer> disponibles = new LinkedHashMap<>();
        if (r != null && r.items() != null) {
            r.items().stream().filter(i -> i.sku() != null && i.available() != null)
                    .forEach(i -> disponibles.merge(i.sku(), i.available(), Integer::sum));
        }
        // Un sku que Productos no devuelve cuenta como sin stock
        boolean hay = pedidas.entrySet().stream()
                .allMatch(e -> disponibles.getOrDefault(e.getKey(), 0) >= e.getValue());
        log.info("Disponibilidad en Productos de {} -> {} ({})", pedidas, hay, disponibles);
        return hay;
    }

    /**
     * Devuelve al inventario las unidades aptas de una recepción física. Productos responde síncrono (COMPLETED).
     * Mismo operationId en un reintento: Productos no reintegra dos veces. Si no se pudo, 503 PRODUCTOS_NO_DISPONIBLE
     * y la recepción queda pendiente para reintentarla.
     */
    public void reintegrar(UUID operationId, String pedidoId, String devolucionId, List<LineaReintegro> lineas) {
        if (simulado) {
            log.info("[simulado] reintegro {} de {} por la devolución {}", operationId, lineas, devolucionId);
            return;
        }
        if (ubicacion.isEmpty()) {
            throw new DependenciaNoDisponibleException("PRODUCTOS_NO_DISPONIBLE",
                    "Falta el location_id del almacén de devoluciones de Productos; el reintegro queda pendiente");
        }
        ReintegroRequest cuerpo = new ReintegroRequest(operationId, pedidoId, devolucionId, MOTIVO_DEVOLUCION,
                lineas.stream().map(l -> new LineaRequest(l.sku(), l.cantidad(), ubicacion)).toList());
        try {
            http.post().uri("/api/v1/inventario/reintegros")
                    .headers(h -> tokenServicio.authorization().ifPresent(t -> h.set(HttpHeaders.AUTHORIZATION, t)))
                    .body(cuerpo)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new DependenciaNoDisponibleException("PRODUCTOS_NO_DISPONIBLE",
                    "No se pudo reintegrar el stock en Productos; la recepción queda pendiente para reintentarla", e);
        }
    }
}

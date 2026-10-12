package ventas.integracion;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ventas.compartido.ConflictoException;

/**
 * Módulo F (Productos, G6): reserva de stock antes de registrar el pedido.
 * POST /api/v1/inventario/reservas -> 202. Con integracion.simulado=true solo deja un log.
 * Responsable de la integración real: Luis Arroyo. Michael lo dejó simulado para 1.1.
 */
@Slf4j
@Component
public class ProductosClient {

    public record ItemReserva(String productoId, String sku, int cantidad) {}

    private record ReservaRequest(String referencia, List<ItemReserva> items) {}

    private final boolean simulado;
    private final RestClient http;

    public ProductosClient(@Value("${integracion.simulado:true}") boolean simulado,
                           @Value("${integracion.productos-url:http://localhost:8090}") String url) {
        this.simulado = simulado;
        this.http = RestClient.builder().baseUrl(url).build();
    }

    /** Reserva todos los ítems o lanza 409 ITEM_SIN_DISPONIBILIDAD. La referencia es la Idempotency-Key. */
    public void reservar(String referencia, List<ItemReserva> items) {
        if (simulado) {
            log.info("[simulado] reserva de stock {} -> {}", referencia, items);
            return;
        }
        try {
            http.post().uri("/api/v1/inventario/reservas")
                    .body(new ReservaRequest(referencia, items))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT || e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                throw new ConflictoException("ITEM_SIN_DISPONIBILIDAD", "Uno o más ítems no tienen stock disponible");
            }
            throw new IllegalStateException("Productos rechazó la reserva: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("No se pudo contactar a Productos", e);
        }
    }

    /**
     * Libera una reserva cuando el pedido no llegó a guardarse. Nunca lanza excepción: si falla,
     * solo queda en el log (la reserva vence sola en Productos).
     * Ruta por confirmar con G6 (Luis Arroyo).
     */
    public void liberar(String referencia) {
        if (simulado) {
            log.info("[simulado] liberar reserva de stock {}", referencia);
            return;
        }
        try {
            http.delete().uri("/api/v1/inventario/reservas/{referencia}", referencia)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("No se pudo liberar la reserva {} en Productos: {}", referencia, e.getMessage());
        }
    }
}

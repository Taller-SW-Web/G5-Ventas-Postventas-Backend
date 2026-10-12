package ventas.integracion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ventas.compartido.ConflictoException;

/**
 * Módulo F (Productos, G6), según ACUERDO_G6_PRODUCTOS:
 * - Reservar: POST /api/v1/inventario/reservas -> 202. El resultado y el reservaId llegan después por RabbitMQ (po.sales.q).
 * - Confirmar: POST /api/v1/inventario/{reservaId}/confirmar cuando el pedido pasa a PAGADO.
 * - Liberar: POST /api/v1/inventario/{reservaId}/liberar en anulaciones antes del consumo.
 * Retail no usa nada de esto (acuerdo G4, R9). Con integracion.simulado=true no se llama a nadie.
 */
@Slf4j
@Component
public class ProductosClient {

    public record ItemReserva(String productoId, String sku, int cantidad) {}

    /** Resultado de una reserva: lo trae po.sales.q, o lo inventa el modo simulado. */
    public record ResultadoReserva(String referencia, String reservaId, boolean confirmada) {}

    /** customer_ref = UUID sub de Seguridad (acuerdo G6). */
    private record ReservaRequest(String referencia, UUID customerRef, List<ItemReserva> items) {}

    private final boolean simulado;
    private final RestClient http;

    public ProductosClient(@Value("${integracion.simulado:true}") boolean simulado,
                           @Value("${integracion.productos-url:http://localhost:8090}") String url) {
        this.simulado = simulado;
        this.http = RestClient.builder().baseUrl(url).build();
    }

    /**
     * Pide la reserva. La referencia es la Idempotency-Key del pedido: con ella se encuentra el pedido
     * cuando llega la respuesta. En modo simulado devuelve el resultado al instante (reserva confirmada);
     * en modo real devuelve vacío porque el resultado llega por RabbitMQ.
     * Si Productos rechaza el comando en el acto (409/422) lanza 409 ITEM_SIN_DISPONIBILIDAD.
     */
    public Optional<ResultadoReserva> reservar(String referencia, UUID customerRef, List<ItemReserva> items) {
        if (simulado) {
            log.info("[simulado] reserva de stock {} -> {}", referencia, items);
            return Optional.of(new ResultadoReserva(referencia, "SIM-" + referencia, true));
        }
        try {
            http.post().uri("/api/v1/inventario/reservas")
                    .body(new ReservaRequest(referencia, customerRef, items))
                    .retrieve()
                    .toBodilessEntity();
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT || e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                throw new ConflictoException("ITEM_SIN_DISPONIBILIDAD", "Uno o más ítems no tienen stock disponible");
            }
            throw new IllegalStateException("Productos rechazó la reserva: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("No se pudo contactar a Productos", e);
        }
    }

    /** Consume la reserva cuando el pedido se paga. Nunca lanza excepción: si falla, queda en el log. */
    public void confirmar(String reservaId) {
        llamar("/api/v1/inventario/{reservaId}/confirmar", reservaId, "confirmar");
    }

    /** Devuelve el stock reservado cuando el pedido se anula antes del consumo. Nunca lanza excepción. */
    public void liberar(String reservaId) {
        llamar("/api/v1/inventario/{reservaId}/liberar", reservaId, "liberar");
    }

    private void llamar(String ruta, String reservaId, String accion) {
        if (simulado) {
            log.info("[simulado] {} reserva {}", accion, reservaId);
            return;
        }
        try {
            http.post().uri(ruta, reservaId).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("No se pudo {} la reserva {} en Productos: {}", accion, reservaId, e.getMessage());
        }
    }
}

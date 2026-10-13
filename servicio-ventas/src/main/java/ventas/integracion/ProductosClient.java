package ventas.integracion;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ventas.compartido.DependenciaNoDisponibleException;

/**
 * Módulo F (Productos, G6), según su openapi.yaml (base /api/v1). Las tres operaciones responden 202:
 * el resultado llega después por RabbitMQ (po.sales.q, ver MensajeInventario).
 * - Reservar: POST /inventario/reservas, al crear el pedido (no Retail, acuerdo G4 R9).
 * - Confirmar: POST /inventario/reservas/{reservaId}/confirmar, cuando el pedido pasa a PAGADO.
 * - Liberar: POST /inventario/reservas/{reservaId}/liberar, en anulaciones antes del consumo.
 * operation_id e Idempotency-Key salen del código del pedido: un reintento nunca duplica la operación.
 * Con integracion.simulado=true no se llama a nadie y la reserva se da por creada al instante.
 */
@Slf4j
@Component
public class ProductosClient {

    public enum Operacion { RESERVAR, CONFIRMAR, LIBERAR }

    /** reason de LiberarReservaRequest. */
    public enum MotivoLiberacion { PAGO_NO_COMPLETADO, ANULACION, EXPIRACION_FORZADA, OTRO }

    public enum ResultadoConfirmacion { ACEPTADA, RESERVA_EXPIRADA, NO_ENVIADA }

    public record LineaReserva(String sku, int cantidad) {}

    /** Scopes de api-productos (openapi de G6, x-required-scope). Seguridad aún debe registrarlos para modulo-ventas. */
    static final String SCOPE_RESERVAR = "inventario:reservar";
    static final String SCOPE_CONSUMIR = "inventario:consumir";
    static final String SCOPE_LIBERAR = "inventario:liberar";

    private final boolean simulado;
    private final RestClient http;
    private final TokenServicio tokens;

    public ProductosClient(@Value("${integracion.simulado:true}") boolean simulado,
                           @Value("${integracion.productos-url:http://localhost:8090}") String url,
                           RestClient.Builder builder,
                           TokenServicio tokens) {
        this.simulado = simulado;
        this.http = builder.baseUrl(url + "/api/v1").build();
        this.tokens = tokens;
    }

    /** Siempre el mismo UUID para la misma operación sobre el mismo pedido. */
    public static String operacionId(Operacion operacion, String pedidoId) {
        return UUID.nameUUIDFromBytes((operacion + ":" + pedidoId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Pide la reserva. Se llama dentro de la transacción que guarda el pedido: si Productos no admite el comando
     * lanza 503 PRODUCTOS_NO_DISPONIBLE y el pedido no se guarda (RNF-05). La falta de stock NO llega aquí:
     * llega después como inventory.consumption.rejected. En modo simulado devuelve el mensaje de reserva creada.
     */
    public Optional<MensajeInventario> reservar(String pedidoId, String canal, List<LineaReserva> lineas) {
        String operacion = operacionId(Operacion.RESERVAR, pedidoId);
        if (simulado) {
            log.info("[simulado] reserva de stock de {} -> {}", pedidoId, lineas);
            return Optional.of(new MensajeInventario("SIM-" + operacion, MensajeInventario.RESERVA_CREADA, operacion,
                    Map.of(MensajeInventario.CAMPO_PEDIDO, pedidoId, MensajeInventario.CAMPO_RESERVA, "SIM-" + pedidoId)));
        }
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("order_id", pedidoId);
        cuerpo.put("operation_id", operacion);
        cuerpo.put("channel_id", canal);
        // location_id se omite: un solo almacén central (decisión del equipo, pendiente de confirmar con Productos)
        cuerpo.put("lines", lineas.stream().map(l -> Map.of("sku", l.sku(), "quantity", l.cantidad())).toList());
        try {
            http.post().uri("/inventario/reservas")
                    .headers(tokens.autorizacion(SCOPE_RESERVAR))
                    .header("Idempotency-Key", operacion)
                    .body(cuerpo)
                    .retrieve()
                    .toBodilessEntity();
            return Optional.empty();
        } catch (RestClientException e) {
            tokens.revisar(e, SCOPE_RESERVAR);
            log.error("Productos no admitió la reserva de {}: {}", pedidoId, e.getMessage());
            throw new DependenciaNoDisponibleException("PRODUCTOS_NO_DISPONIBLE",
                    "No se pudo reservar el stock en Productos; intente nuevamente", e);
        }
    }

    /**
     * Consume la reserva cuando el pedido se paga. RESERVA_EXPIRADA: Productos la rechazó al instante (409);
     * el resto de rechazos llegan por RabbitMQ. Nunca lanza excepción.
     */
    public ResultadoConfirmacion confirmar(String pedidoId, String reservaId) {
        if (simulado) {
            log.info("[simulado] confirmar reserva {} de {}", reservaId, pedidoId);
            return ResultadoConfirmacion.ACEPTADA;
        }
        String operacion = operacionId(Operacion.CONFIRMAR, pedidoId);
        try {
            http.post().uri("/inventario/reservas/{reservaId}/confirmar", reservaId)
                    .headers(tokens.autorizacion(SCOPE_CONSUMIR))
                    .header("Idempotency-Key", operacion)
                    .body(Map.of("order_id", pedidoId, "operation_id", operacion))
                    .retrieve()
                    .toBodilessEntity();
            return ResultadoConfirmacion.ACEPTADA;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT && e.getResponseBodyAsString().contains("RESERVA_EXPIRADA")) {
                return ResultadoConfirmacion.RESERVA_EXPIRADA;
            }
            tokens.revisar(e, SCOPE_CONSUMIR);
            log.warn("Productos rechazó confirmar la reserva {} de {}: {}", reservaId, pedidoId, e.getMessage());
            return ResultadoConfirmacion.NO_ENVIADA;
        } catch (RestClientException e) {
            log.warn("No se pudo confirmar la reserva {} de {}: {}", reservaId, pedidoId, e.getMessage());
            return ResultadoConfirmacion.NO_ENVIADA;
        }
    }

    /** Devuelve el stock reservado cuando el pedido se anula antes del consumo. Nunca lanza excepción. */
    public void liberar(String pedidoId, String reservaId, MotivoLiberacion motivo) {
        if (simulado) {
            log.info("[simulado] liberar reserva {} de {} ({})", reservaId, pedidoId, motivo);
            return;
        }
        String operacion = operacionId(Operacion.LIBERAR, pedidoId);
        try {
            http.post().uri("/inventario/reservas/{reservaId}/liberar", reservaId)
                    .headers(tokens.autorizacion(SCOPE_LIBERAR))
                    .header("Idempotency-Key", operacion)
                    .body(Map.of("order_id", pedidoId, "operation_id", operacion, "reason", motivo.name()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            tokens.revisar(e, SCOPE_LIBERAR);
            log.warn("No se pudo liberar la reserva {} de {}: {}", reservaId, pedidoId, e.getMessage());
        }
    }
}

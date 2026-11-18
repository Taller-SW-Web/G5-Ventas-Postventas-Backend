package ventas.integracion;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ventas.compartido.DependenciaNoDisponibleException;

/**
 * Módulo F (Productos, G6), según su openapi.yaml (base /api/v1). Las tres operaciones responden 202:
 * el resultado se consulta después por HTTP (contrato H5: GET /inventario/operaciones/{operationId}, ver
 * SeguimientoProductos) o, con integracion.productos-transporte=rabbit, llega por RabbitMQ (po.sales.q).
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

    /**
     * Resultado durable de una operación de Inventory (GET /inventario/operaciones/{operationId}, contrato H5 §6.4).
     * status: ACCEPTED, PROCESSING, COMPLETED, REJECTED o REQUIRES_REVIEW.
     */
    public record ResultadoOperacion(String operationId, String operationType, String status,
                                     String reservationId, String resultCode) {
        public boolean completada() {
            return "COMPLETED".equals(status);
        }

        public boolean rechazada() {
            return "REJECTED".equals(status);
        }
    }

    /** Estado actual de una reserva (GET /inventario/reservas/{reservaId}/estado, contrato H5 §7.4). */
    public record EstadoReserva(String estadoPersistido, String vigenciaEfectiva) {
        /** ACTIVA pero ya vencida, o EXPIRADA persistida: el pedido no puede seguir (contrato H5 §8.2). */
        public boolean vencida() {
            return "VENCIDA".equals(vigenciaEfectiva) || "EXPIRADA".equals(estadoPersistido);
        }
    }

    private final boolean simulado;
    private final RestClient http;
    private final TokenServicio tokens;
    private final String locationId;

    public ProductosClient(boolean simulado, String url, RestClient.Builder builder, TokenServicio tokens) {
        this(simulado, url, "", builder, tokens);
    }

    @Autowired
    public ProductosClient(@Value("${integracion.simulado:true}") boolean simulado,
                           @Value("${integracion.productos-url:http://localhost:8090}") String url,
                           @Value("${integracion.productos-location-id:}") String locationId,
                           RestClient.Builder builder,
                           TokenServicio tokens) {
        this.simulado = simulado;
        this.http = builder.baseUrl(url + "/api/v1").build();
        this.tokens = tokens;
        this.locationId = locationId == null ? "" : locationId.trim();
    }

    /**
     * Consulta el resultado de una operación (reservar, confirmar o liberar). Vacío si Productos todavía no la
     * conoce (404) o no respondió: el seguimiento vuelve a preguntar en el siguiente barrido.
     */
    public Optional<ResultadoOperacion> consultarOperacion(String operationId) {
        if (simulado) {
            return Optional.empty();
        }
        try {
            JsonNode n = http.get().uri("/inventario/operaciones/{operationId}", operationId)
                    .headers(tokens.autorizacion())
                    .retrieve()
                    .body(JsonNode.class);
            if (n == null) {
                return Optional.empty();
            }
            return Optional.of(new ResultadoOperacion(texto(n, "operation_id"), texto(n, "operation_type"),
                    texto(n, "status"), texto(n, "reservation_id"), texto(n, "result_code")));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            tokens.revisar(e);
            log.warn("No se pudo consultar la operación {} en Productos: {}", operationId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Vigencia actual de la reserva. Vacío si no se pudo leer: el seguimiento lo intenta de nuevo después. */
    public Optional<EstadoReserva> consultarReserva(String reservaId, String pedidoId) {
        if (simulado) {
            return Optional.empty();
        }
        try {
            JsonNode n = http.get().uri("/inventario/reservas/{reservaId}/estado?order_id={pedido}", reservaId, pedidoId)
                    .headers(tokens.autorizacion())
                    .retrieve()
                    .body(JsonNode.class);
            return n == null ? Optional.empty()
                    : Optional.of(new EstadoReserva(texto(n, "estado_persistido"), texto(n, "vigencia_efectiva")));
        } catch (RestClientException e) {
            tokens.revisar(e);
            log.warn("No se pudo consultar la reserva {} de {}: {}", reservaId, pedidoId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String texto(JsonNode n, String campo) {
        return n.hasNonNull(campo) ? n.get(campo).asText() : null;
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
        // location_id: almacén acordado con Productos por entorno (ALM-LURIN, contrato H5 §3); vacío = se omite
        cuerpo.put("lines", lineas.stream().map(this::linea).toList());
        try {
            http.post().uri("/inventario/reservas")
                    .headers(tokens.autorizacion())
                    .header("Idempotency-Key", operacion)
                    .body(cuerpo)
                    .retrieve()
                    .toBodilessEntity();
            return Optional.empty();
        } catch (RestClientException e) {
            tokens.revisar(e);
            log.error("Productos no admitió la reserva de {}: {}", pedidoId, e.getMessage());
            throw new DependenciaNoDisponibleException("PRODUCTOS_NO_DISPONIBLE",
                    "No se pudo reservar el stock en Productos; intente nuevamente", e);
        }
    }

    private Map<String, Object> linea(LineaReserva l) {
        Map<String, Object> linea = new HashMap<>();
        linea.put("sku", l.sku());
        linea.put("quantity", l.cantidad());
        if (!locationId.isEmpty()) {
            linea.put("location_id", locationId);
        }
        return linea;
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
                    .headers(tokens.autorizacion())
                    .header("Idempotency-Key", operacion)
                    .body(Map.of("order_id", pedidoId, "operation_id", operacion))
                    .retrieve()
                    .toBodilessEntity();
            return ResultadoConfirmacion.ACEPTADA;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT && e.getResponseBodyAsString().contains("RESERVA_EXPIRADA")) {
                return ResultadoConfirmacion.RESERVA_EXPIRADA;
            }
            tokens.revisar(e);
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
                    .headers(tokens.autorizacion())
                    .header("Idempotency-Key", operacion)
                    .body(Map.of("order_id", pedidoId, "operation_id", operacion, "reason", motivo.name()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            tokens.revisar(e);
            log.warn("No se pudo liberar la reserva {} de {}: {}", reservaId, pedidoId, e.getMessage());
        }
    }
}

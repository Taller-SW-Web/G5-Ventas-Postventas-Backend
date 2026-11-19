package ventas.integracion;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cupones de Productos (G6). Dos transportes, según integracion.productos-transporte:
 * - http (contrato H5, por defecto): POST /cupones/consumos y POST /cupones/restauraciones (202, Idempotency-Key igual
 *   al operation_id del cuerpo); el resultado se consulta con GET /cupones/operaciones/{operationId}?order_id=...
 *   (SeguimientoProductos lo convierte en el mismo MensajeInventario que llegaba por RabbitMQ).
 * - rabbit (H4): promotions.coupon.consumption.requested y promotions.coupon.restoration.requested en po.commands.x;
 *   el resultado llega por po.sales.q.
 * El consumo se pide después de que la reserva queda creada y antes de habilitar el pago; la restauración, cuando
 * un pedido con cupón se anula. customer_ref = contacto.clienteId (sub de Seguridad).
 * Con integracion.simulado=true no se llama a nadie: consumir() devuelve al instante el "completed" simulado.
 */
@Slf4j
@Component
public class CuponesClient {

    public static final String CONSUMO_PEDIDO = "promotions.coupon.consumption.requested";
    public static final String RESTAURACION_PEDIDA = "promotions.coupon.restoration.requested";

    /** Snapshot H5 de una operación de cupón: status ACCEPTED, PROCESSING, COMPLETED o REJECTED. */
    public record ResultadoCupon(String status, String resultCode) {
        /** Productos respondió 404: no conoce la operación (el POST nunca llegó). */
        public static final ResultadoCupon NO_ENCONTRADA = new ResultadoCupon("NOT_FOUND", null);

        public boolean noEncontrada() {
            return NO_ENCONTRADA.status().equals(status);
        }

        public boolean completada() {
            return "COMPLETED".equals(status);
        }

        public boolean rechazada() {
            return "REJECTED".equals(status);
        }
    }

    private final boolean simulado;
    private final boolean porHttp;
    private final String exchange;
    private final ObjectProvider<RabbitTemplate> rabbit;
    private final ObjectMapper json;
    private final RestClient http;
    private final TokenServicio tokens;

    /** Solo RabbitMQ (H4), para pruebas. */
    public CuponesClient(boolean simulado, String exchange, ObjectProvider<RabbitTemplate> rabbit, ObjectMapper json) {
        this(simulado, "rabbit", exchange, "http://localhost:8090", rabbit, json, RestClient.builder(),
                TokenServicio.sinCredenciales());
    }

    @Autowired
    public CuponesClient(@Value("${integracion.simulado:true}") boolean simulado,
                         @Value("${integracion.productos-transporte:http}") String transporte,
                         @Value("${integracion.productos-comandos:po.commands.x}") String exchange,
                         @Value("${integracion.productos-url:http://localhost:8090}") String url,
                         ObjectProvider<RabbitTemplate> rabbit,
                         ObjectMapper json,
                         RestClient.Builder builder,
                         TokenServicio tokens) {
        this.simulado = simulado;
        this.porHttp = !"rabbit".equalsIgnoreCase(transporte == null ? "" : transporte.trim());
        this.exchange = exchange;
        this.rabbit = rabbit;
        this.json = json;
        this.http = builder.baseUrl(url + "/api/v1").build();
        this.tokens = tokens;
    }

    /** Misma clave para el mismo pedido: Productos no consume dos veces el cupón si se reenvía. */
    public static String operacionConsumo(String pedidoId) {
        return UUID.nameUUIDFromBytes(("CUPON:" + pedidoId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String operacionRestauracion(String pedidoId) {
        return UUID.nameUUIDFromBytes(("RESTAURAR_CUPON:" + pedidoId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Transporte H4 (sin reserva en el cuerpo). Se conserva para el modo rabbit. */
    public Optional<MensajeInventario> consumir(String pedidoId, String codigoCupon, UUID clienteId, String canal,
                                                OffsetDateTime fechaPedido) {
        return consumir(pedidoId, codigoCupon, clienteId, canal, fechaPedido, null);
    }

    /**
     * Pide el consumo del cupón. En modo simulado devuelve el "completed" para aplicarlo enseguida.
     * Por HTTP, un fallo de red no se pierde: SeguimientoProductos reenvía la misma operación si Productos no la
     * conoce (404), con la misma clave y el mismo cuerpo.
     */
    public Optional<MensajeInventario> consumir(String pedidoId, String codigoCupon, UUID clienteId, String canal,
                                                OffsetDateTime fechaPedido, String reservaId) {
        String operacion = operacionConsumo(pedidoId);
        if (simulado) {
            log.info("[simulado] consumo del cupón {} del pedido {}", codigoCupon, pedidoId);
            return Optional.of(new MensajeInventario("SIM-" + operacion, MensajeInventario.CUPON_CONSUMIDO, operacion,
                    Map.of(MensajeInventario.CAMPO_PEDIDO, pedidoId)));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("order_id", pedidoId);
        data.put("coupon_code", codigoCupon);
        data.put("customer_ref", clienteId == null ? null : clienteId.toString());
        data.put("channel_id", canal);
        data.put("effective_at", fechaPedido.withOffsetSameInstant(ZoneOffset.UTC).toString());
        if (porHttp) {
            data.put("operation_id", operacion);
            data.put("reservation_id", reservaId);
            data.put("commercial_snapshot_ref", pedidoId);   // el pedido inmutable (contrato H5 §5)
            postear("/cupones/consumos", operacion, pedidoId, data);
        } else {
            publicar(CONSUMO_PEDIDO, pedidoId, operacion, data);
        }
        return Optional.empty();
    }

    /** Nunca lanza excepción: si no se pudo enviar queda en el log (el contador del cupón no se restaura). */
    public void restaurar(String pedidoId, String motivo) {
        if (simulado) {
            log.info("[simulado] restauración del cupón del pedido {} ({})", pedidoId, motivo);
            return;
        }
        String operacion = operacionRestauracion(pedidoId);
        Map<String, Object> data = new HashMap<>();
        data.put("order_id", pedidoId);
        data.put("cancellation_reason", motivo);
        data.put("cancelled_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        try {
            if (porHttp) {
                data.put("operation_id", operacion);
                data.put("consumption_operation_id", operacionConsumo(pedidoId));   // el consumo original
                postear("/cupones/restauraciones", operacion, pedidoId, data);
            } else {
                publicar(RESTAURACION_PEDIDA, pedidoId, operacion, data);
            }
        } catch (RuntimeException e) {
            log.error("No se pudo pedir la restauración del cupón de {}: {}", pedidoId, e.getMessage());
        }
    }

    /**
     * Resultado durable de una operación de cupón (H5 §7.3). NO_ENCONTRADA si Productos no la conoce (404); vacío si
     * no respondió (un error de red no dice nada del cupón). Solo con transporte http; en rabbit y simulado, vacío.
     */
    public Optional<ResultadoCupon> consultar(String operationId, String pedidoId) {
        if (simulado || !porHttp) {
            return Optional.empty();
        }
        try {
            JsonNode n = http.get().uri("/cupones/operaciones/{operationId}?order_id={pedido}", operationId, pedidoId)
                    .headers(tokens.autorizacion())
                    .retrieve()
                    .body(JsonNode.class);
            if (n == null) {
                return Optional.empty();
            }
            JsonNode r = n.get("result");
            String codigo = r != null && r.hasNonNull("result_code") ? r.get("result_code").asText() : null;
            return Optional.of(new ResultadoCupon(n.hasNonNull("status") ? n.get("status").asText() : null, codigo));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.of(ResultadoCupon.NO_ENCONTRADA);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw LimiteProductosException.desde(e);
        } catch (RestClientException e) {
            tokens.revisar(e);
            log.warn("No se pudo consultar la operación de cupón {} de {}: {}", operationId, pedidoId, e.getMessage());
            return Optional.empty();
        }
    }

    /** true si la transporta HTTP (contrato H5). */
    public boolean porHttp() {
        return porHttp;
    }

    /** 202 (admitida) o 200 (replay idéntico): ambos dejan la operación en curso. Un fallo solo se registra. */
    private void postear(String ruta, String operacion, String pedidoId, Map<String, Object> cuerpo) {
        try {
            http.post().uri(ruta)
                    .headers(tokens.autorizacion())
                    .header("Idempotency-Key", operacion)
                    .body(cuerpo)
                    .retrieve()
                    .toBodilessEntity();
            log.info("POST {} admitido para {} (operation_id {})", ruta, pedidoId, operacion);
        } catch (RestClientException e) {
            tokens.revisar(e);
            log.warn("Productos no admitió POST {} de {} (operation_id {}): {}", ruta, pedidoId, operacion, e.getMessage());
        }
    }

    /** Sobre MessageEnvelope del asyncapi de G6. message_id nuevo en cada envío; operation_id fijo por pedido. */
    Map<String, Object> sobre(String nombre, String pedidoId, String operacion, Map<String, Object> data) {
        Map<String, Object> sobre = new HashMap<>();
        sobre.put("message_id", UUID.randomUUID().toString());
        sobre.put("schema_version", 1);
        sobre.put("occurred_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        sobre.put("correlation_id", pedidoId);
        sobre.put("causation_id", null);
        sobre.put("operation_id", operacion);
        sobre.put("producer", "modulo-ventas");
        sobre.put("kind", "command");
        sobre.put("name", nombre);
        sobre.put("data", data);
        return sobre;
    }

    private void publicar(String nombre, String pedidoId, String operacion, Map<String, Object> data) {
        Map<String, Object> sobre = sobre(nombre, pedidoId, operacion, data);
        try {
            byte[] cuerpo = json.writeValueAsBytes(sobre);
            rabbit.getObject().send(exchange, nombre, MessageBuilder.withBody(cuerpo)
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setMessageId(sobre.get("message_id").toString())
                    .build());
            log.info("{} publicado para {} (operation_id {})", nombre, pedidoId, operacion);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar " + nombre, e);
        }
    }
}

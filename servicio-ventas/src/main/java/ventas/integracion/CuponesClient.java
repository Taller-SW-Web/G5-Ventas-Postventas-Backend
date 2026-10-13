package ventas.integracion;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cupones de Productos (G6, kit §18 y asyncapi P1): solo por RabbitMQ, sin HTTP.
 * - promotions.coupon.consumption.requested: después de que la reserva queda creada y antes de habilitar el pago.
 * - promotions.coupon.restoration.requested: cuando un pedido con cupón se anula (la política del cupón decide si
 *   el contador se restaura; si nunca se consumió, Productos responde NO_CONSUMPTION).
 * Se publican en po.commands.x con routing key = nombre del mensaje y el sobre MessageEnvelope.
 * El resultado llega por po.sales.q (MensajesProductosService). customer_ref = contacto.clienteId (sub de Seguridad).
 * Con integracion.simulado=true no publica nada: consumir() devuelve al instante el "completed" simulado.
 */
@Slf4j
@Component
public class CuponesClient {

    public static final String CONSUMO_PEDIDO = "promotions.coupon.consumption.requested";
    public static final String RESTAURACION_PEDIDA = "promotions.coupon.restoration.requested";

    private final boolean simulado;
    private final String exchange;
    private final ObjectProvider<RabbitTemplate> rabbit;
    private final ObjectMapper json;

    public CuponesClient(@Value("${integracion.simulado:true}") boolean simulado,
                         @Value("${integracion.productos-comandos:po.commands.x}") String exchange,
                         ObjectProvider<RabbitTemplate> rabbit,
                         ObjectMapper json) {
        this.simulado = simulado;
        this.exchange = exchange;
        this.rabbit = rabbit;
        this.json = json;
    }

    /** Misma clave para el mismo pedido: Productos no consume dos veces el cupón si se reenvía. */
    public static String operacionConsumo(String pedidoId) {
        return UUID.nameUUIDFromBytes(("CUPON:" + pedidoId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String operacionRestauracion(String pedidoId) {
        return UUID.nameUUIDFromBytes(("RESTAURAR_CUPON:" + pedidoId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** En modo simulado devuelve el resultado "completed" para aplicarlo enseguida, como la reserva simulada. */
    public Optional<MensajeInventario> consumir(String pedidoId, String codigoCupon, UUID clienteId, String canal,
                                                OffsetDateTime fechaPedido) {
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
        publicar(CONSUMO_PEDIDO, pedidoId, operacion, data);
        return Optional.empty();
    }

    /** Nunca lanza excepción: si no se pudo publicar queda en el log (el contador del cupón no se restaura). */
    public void restaurar(String pedidoId, String motivo) {
        if (simulado) {
            log.info("[simulado] restauración del cupón del pedido {} ({})", pedidoId, motivo);
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("order_id", pedidoId);
        data.put("cancellation_reason", motivo);
        data.put("cancelled_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        try {
            publicar(RESTAURACION_PEDIDA, pedidoId, operacionRestauracion(pedidoId), data);
        } catch (RuntimeException e) {
            log.error("No se pudo pedir la restauración del cupón de {}: {}", pedidoId, e.getMessage());
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

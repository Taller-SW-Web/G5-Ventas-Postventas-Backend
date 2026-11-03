package ventas.integracion;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import ventas.pedido.MensajesProductosService;
import ventas.pedido.MensajesProductosService.Desenlace;

/**
 * Lee po.sales.q del RabbitMQ de Productos (vhost g6-productos-ofertas, rabbitmq-topologia.md) y se lo pasa a
 * MensajesProductosService. Solo existe con integracion.simulado=false: en modo simulado no se conecta a nada.
 * - Mensaje mal formado: se rechaza sin reintentar (va a la DLQ).
 * - Pedido todavía no guardado: se lanza error y el contenedor reintenta (spring.rabbitmq.listener.simple.retry).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "integracion.simulado", havingValue = "false")
@RequiredArgsConstructor
public class ConsumidorProductos {

    private static final TypeReference<Map<String, Object>> MAPA = new TypeReference<>() {};

    private final MensajesProductosService mensajes;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${integracion.productos-cola:po.sales.q}")
    public void recibir(Message mensaje) {
        MensajeInventario m = leer(mensaje.getBody());
        Desenlace d = mensajes.procesar(m);
        if (d == Desenlace.PEDIDO_NO_ENCONTRADO) {
            throw new IllegalStateException("Pedido " + m.pedidoId() + " todavía no existe; se reintenta " + m.messageId());
        }
        log.debug("Mensaje {} ({}) de {}: {}", m.messageId(), m.name(), m.pedidoId(), d);
    }

    /** Sobre de asyncapi.yaml (MessageEnvelope): message_id, name, operation_id y data. */
    MensajeInventario leer(byte[] cuerpo) {
        try {
            JsonNode n = objectMapper.readTree(cuerpo);
            String messageId = texto(n, "message_id");
            String name = texto(n, "name");
            if (messageId == null || name == null) {
                throw new AmqpRejectAndDontRequeueException("Mensaje sin message_id o name");
            }
            Map<String, Object> data = n.hasNonNull("data") ? objectMapper.convertValue(n.get("data"), MAPA) : Map.of();
            return new MensajeInventario(messageId, name, texto(n, "operation_id"), data);
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("Mensaje de Productos que no es JSON", e);
        }
    }

    private static String texto(JsonNode n, String campo) {
        return n.hasNonNull(campo) ? n.get(campo).asText() : null;
    }
}

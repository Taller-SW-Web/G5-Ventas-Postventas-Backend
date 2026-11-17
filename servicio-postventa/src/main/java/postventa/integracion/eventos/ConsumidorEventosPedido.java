package postventa.integracion.eventos;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import postventa.compartido.ConflictoException;
import postventa.compartido.SolicitudInvalidaException;

/**
 * Lee los eventos de pedido que publica M1 en postventa.eventos-pedido.q y se los pasa a
 * EventoPedidoService, igual que POST /api/v2/eventos. El cuerpo es el mismo JSON del outbox de M1.
 * Mismo criterio que M1 aplica a las respuestas HTTP (EnvioEventosScheduler):
 * - Nuevo o repetido: ack. La idempotencia por idEvento la resuelve el servicio.
 * - JSON inválido, evento que no pasa la validación (400) o en conflicto (409): directo a la DLQ, sin reintentos.
 * - Cualquier otro error (base de datos, 404, 503): se reintenta y, agotados los intentos, va a la DLQ.
 * Solo existe con eventos.asincronos=true.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "eventos.asincronos", havingValue = "true")
public class ConsumidorEventosPedido {

    private final EventoPedidoService servicio;
    private final ObjectMapper objectMapper;

    public ConsumidorEventosPedido(EventoPedidoService servicio, ObjectMapper objectMapper) {
        this.servicio = Objects.requireNonNull(servicio);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @RabbitListener(queues = EventosRabbitConfig.COLA, containerFactory = EventosRabbitConfig.LISTENER_FACTORY)
    public void recibir(Message mensaje) {
        EventoPedidoPayload evento = leer(mensaje.getBody());
        try {
            boolean nuevo = servicio.recibir(evento);
            log.debug("Evento {} ({}) del pedido {}: {}", evento.idEvento(), evento.tipo(), evento.pedidoId(),
                    nuevo ? "procesado" : "repetido");
        } catch (SolicitudInvalidaException | ConflictoException e) {
            log.error("Evento {} rechazado, va a la DLQ: {}", evento.idEvento(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Evento " + evento.idEvento() + " rechazado", e);
        }
    }

    EventoPedidoPayload leer(byte[] cuerpo) {
        try {
            EventoPedidoPayload evento = objectMapper.readValue(cuerpo, EventoPedidoPayload.class);
            if (evento == null) {
                throw new AmqpRejectAndDontRequeueException("Mensaje de evento vacío");
            }
            return evento;
        } catch (IOException e) {
            log.error("Mensaje de evento que no es un JSON válido, va a la DLQ: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Mensaje de evento que no es un JSON válido", e);
        }
    }
}

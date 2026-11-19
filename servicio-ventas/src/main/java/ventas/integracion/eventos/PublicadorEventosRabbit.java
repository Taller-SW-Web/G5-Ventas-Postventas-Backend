package ventas.integracion.eventos;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import ventas.integracion.PostventaClient.Envio;

/**
 * Solo acepta un envío con confirm positivo y sin devolución por falta de ruta.
 * No hay respaldo HTTP automático: con EVENTOS_ASINCRONOS=true los eventos van solo por RabbitMQ. Si el broker
 * no confirma, el evento queda pendiente en el outbox y se reintenta por RabbitMQ en la siguiente vuelta.
 * El exchange se declara una vez; si falla un envío se vuelve a declarar en el siguiente (p. ej. tras perder
 * la conexión o si el broker se reinició).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "integracion.eventos.asincronos", havingValue = "true")
public class PublicadorEventosRabbit implements TransporteEventosPedido {
    private final RabbitTemplate rabbit;
    private final RabbitAdmin admin;
    private final long esperaMs;
    private volatile boolean exchangeDeclarado;

    @Autowired
    public PublicadorEventosRabbit(ConexionEventosPedido conexion) {
        this(conexion.plantilla(), new RabbitAdmin(conexion.plantilla()), 5000);
    }

    PublicadorEventosRabbit(RabbitTemplate rabbit, RabbitAdmin admin, long esperaMs) {
        this.rabbit = rabbit;
        this.admin = admin;
        this.esperaMs = esperaMs;
    }

    @Override
    public Envio enviar(OutboxEvento evento) {
        Message mensaje = MessageBuilder.withBody(evento.getPayload().getBytes(UTF_8))
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setMessageId(evento.getIdEvento().toString())
                .setType(evento.getTipo().getValor())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        CorrelationData correlacion = new CorrelationData(evento.getIdEvento().toString());
        try {
            // M2 declara las colas y la DLQ. M1 declara únicamente el exchange acordado.
            declararExchange();
            rabbit.send(EventosPedidoRabbitConfig.EXCHANGE, evento.getTipo().getValor(), mensaje, correlacion);
            CorrelationData.Confirm confirmacion = correlacion.getFuture().get(esperaMs, TimeUnit.MILLISECONDS);
            if (confirmacion.isAck() && correlacion.getReturned() == null) {
                return Envio.ACEPTADO;
            }
            // Un ack sin ruta no implica entrega: mandatory retorna el mensaje antes del confirm.
            log.warn("Evento {} sin confirmacion de enrutamiento; queda pendiente", evento.getIdEvento());
            exchangeDeclarado = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            // No registrar la excepción: puede incluir la URL privada o datos del mensaje.
            log.warn("RabbitMQ no confirmo el evento {}; queda pendiente", evento.getIdEvento());
            exchangeDeclarado = false;
        }
        return Envio.NO_DISPONIBLE;
    }

    private void declararExchange() {
        if (!exchangeDeclarado) {
            admin.declareExchange(new TopicExchange(EventosPedidoRabbitConfig.EXCHANGE, true, false));
            exchangeDeclarado = true;
        }
    }
}

package postventa.integracion.eventos;

import java.util.Map;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * Topología de RabbitMQ para los eventos de pedido de M1 (acuerdos-mecanismo-asincrono.md, sección 3).
 * M2 declara la cola, la DLQ y el DLX; el exchange lo declaran M1 y M2 con los mismos parámetros.
 * Solo existe con eventos.asincronos=true: con false no se abre ninguna conexión.
 */
@Configuration
@ConditionalOnProperty(name = "eventos.asincronos", havingValue = "true")
public class EventosRabbitConfig {

    public static final String EXCHANGE = "ventas.eventos";
    public static final String COLA = "postventa.eventos-pedido.q";
    public static final String DLX = "ventas.eventos.dlx";
    public static final String DLQ = "postventa.eventos-pedido.dlq";
    public static final String BINDING = "pedido.*";
    public static final String LISTENER_FACTORY = "eventosListenerFactory";

    /** 1 intento + 2 reintentos (1 s y 2 s). Agotados, el mensaje va a la DLQ. */
    static final int MAXIMO_INTENTOS = 3;
    static final long ESPERA_INICIAL_MS = 1000;
    static final double MULTIPLICADOR = 2.0;
    static final long ESPERA_MAXIMA_MS = 4000;

    @Bean
    public Declarables topologiaEventosPedido() {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(DLX, true, false);

        Queue cola = QueueBuilder.durable(COLA)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(COLA)
                .build();
        Queue dlq = QueueBuilder.durable(DLQ).build();

        Binding colaAlExchange = BindingBuilder.bind(cola).to(exchange).with(BINDING);
        Binding dlqAlDlx = BindingBuilder.bind(dlq).to(dlx).with(COLA);

        return new Declarables(exchange, dlx, cola, dlq, colaAlExchange, dlqAlDlx);
    }

    /**
     * Fábrica propia del listener: no depende de spring.rabbitmq.listener.* para que el
     * comportamiento ante errores quede fijo y probado (EventosRabbitConfigTest).
     */
    @Bean(LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory eventosListenerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setPrefetchCount(10);
        factory.setDefaultRequeueRejected(false);   // nunca en bucle: rechazado = DLQ
        factory.setAdviceChain(reintentos());
        return factory;
    }

    static Advice reintentos() {
        return RetryInterceptorBuilder.stateless()
                .retryPolicy(politicaReintentos())
                .backOffOptions(ESPERA_INICIAL_MS, MULTIPLICADOR, ESPERA_MAXIMA_MS)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    /**
     * Reintenta todo salvo AmqpRejectAndDontRequeueException (evento inválido o en conflicto),
     * buscándola también como causa porque el contenedor la envuelve en ListenerExecutionFailedException.
     */
    static SimpleRetryPolicy politicaReintentos() {
        return new SimpleRetryPolicy(
                MAXIMO_INTENTOS,
                Map.of(AmqpRejectAndDontRequeueException.class, false),
                true,
                true);
    }
}

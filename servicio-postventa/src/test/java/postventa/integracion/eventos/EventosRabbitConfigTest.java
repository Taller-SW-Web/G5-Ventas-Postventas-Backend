package postventa.integracion.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.retry.support.RetryTemplate;

/**
 * Topología acordada con M1 (acuerdos-mecanismo-asincrono.md, sección 3) y comportamiento ante errores:
 * un mensaje que falla 3 veces, o que se rechaza, termina en la DLQ en vez de perderse.
 */
class EventosRabbitConfigTest {

    private final Declarables topologia = new EventosRabbitConfig().topologiaEventosPedido();

    @Test
    void colaPrincipalEsDurableYDerivaLosRechazadosALaDlq() {
        Queue cola = cola(EventosRabbitConfig.COLA);

        assertThat(cola.isDurable()).isTrue();
        assertThat(cola.getArguments())
                .containsEntry("x-dead-letter-exchange", EventosRabbitConfig.DLX)
                .containsEntry("x-dead-letter-routing-key", EventosRabbitConfig.COLA);
    }

    @Test
    void dlqEsDurableYRecibeLoQueLlegaAlDlx() {
        assertThat(cola(EventosRabbitConfig.DLQ).isDurable()).isTrue();
        assertThat(topologia.getDeclarablesByType(Binding.class))
                .anySatisfy(b -> {
                    assertThat(b.getExchange()).isEqualTo(EventosRabbitConfig.DLX);
                    assertThat(b.getDestination()).isEqualTo(EventosRabbitConfig.DLQ);
                    assertThat(b.getRoutingKey()).isEqualTo(EventosRabbitConfig.COLA);
                });
    }

    @Test
    void colaRecibeTodosLosEventosDePedidoDelExchangeDeVentas() {
        assertThat(topologia.getDeclarablesByType(Binding.class))
                .anySatisfy(b -> {
                    assertThat(b.getExchange()).isEqualTo("ventas.eventos");
                    assertThat(b.getDestination()).isEqualTo("postventa.eventos-pedido.q");
                    assertThat(b.getRoutingKey()).isEqualTo("pedido.*");
                });
        assertThat(topologia.getDeclarablesByType(Exchange.class))
                .anySatisfy(e -> {
                    assertThat(e.getName()).isEqualTo("ventas.eventos");
                    assertThat(e.getType()).isEqualTo("topic");
                    assertThat(e.isDurable()).isTrue();
                });
    }

    @Test
    void errorTemporalSeIntentaTresVecesAntesDeIrALaDlq() {
        AtomicInteger intentos = new AtomicInteger();

        assertThatThrownBy(() -> plantilla().execute(contexto -> {
            intentos.incrementAndGet();
            throw comoLoEnvuelveElContenedor(new IllegalStateException("base no disponible"));
        })).isInstanceOf(ListenerExecutionFailedException.class);

        assertThat(intentos).hasValue(3);
    }

    @Test
    void eventoRechazadoNoSeReintenta() {
        AtomicInteger intentos = new AtomicInteger();

        assertThatThrownBy(() -> plantilla().execute(contexto -> {
            intentos.incrementAndGet();
            throw comoLoEnvuelveElContenedor(new AmqpRejectAndDontRequeueException("evento inválido"));
        })).isInstanceOf(ListenerExecutionFailedException.class);

        assertThat(intentos).hasValue(1);
    }

    @Test
    void errorQueSeRecuperaEnUnReintentoNoLlegaALaDlq() {
        AtomicInteger intentos = new AtomicInteger();

        String resultado = plantilla().execute(contexto -> {
            if (intentos.incrementAndGet() < 2) {
                throw comoLoEnvuelveElContenedor(new IllegalStateException("fallo pasajero"));
            }
            return "procesado";
        });

        assertThat(resultado).isEqualTo("procesado");
        assertThat(intentos).hasValue(2);
    }

    /** Misma política que usa el contenedor, sin las esperas para que la prueba sea rápida. */
    private static RetryTemplate plantilla() {
        RetryTemplate plantilla = new RetryTemplate();
        plantilla.setRetryPolicy(EventosRabbitConfig.politicaReintentos());
        return plantilla;
    }

    private static ListenerExecutionFailedException comoLoEnvuelveElContenedor(Throwable causa) {
        return new ListenerExecutionFailedException("Listener falló", causa,
                new Message(new byte[0], new MessageProperties()));
    }

    private Queue cola(String nombre) {
        return topologia.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals(nombre))
                .findFirst()
                .orElseThrow();
    }
}

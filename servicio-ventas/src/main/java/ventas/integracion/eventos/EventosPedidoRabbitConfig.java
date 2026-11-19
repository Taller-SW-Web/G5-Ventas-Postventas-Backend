package ventas.integracion.eventos;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publicación de eventos M1 → M2. Se habilita explícitamente por entorno. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "integracion.eventos.asincronos", havingValue = "true")
public class EventosPedidoRabbitConfig {

    public static final String EXCHANGE = "ventas.eventos";
    public static final String COLA = "postventa.eventos-pedido.q";
    public static final String DLX = "ventas.eventos.dlx";
    public static final String DLQ = "postventa.eventos-pedido.dlq";

    @Bean(destroyMethod = "close")
    public ConexionEventosPedido conexionEventosPedido(
            @Value("${integracion.eventos.rabbitmq-url:}") String url) {
        validarUrl(url);
        return new ConexionEventosPedido(url);
    }

    private static void validarUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!("amqp".equals(uri.getScheme()) || "amqps".equals(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Configura EVENTOS_RABBITMQ_URL con una URL amqp o amqps para activar los eventos");
        }
    }
}

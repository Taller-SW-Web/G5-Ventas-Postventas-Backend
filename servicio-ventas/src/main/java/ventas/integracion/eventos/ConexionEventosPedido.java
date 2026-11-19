package ventas.integracion.eventos;

import java.net.URI;
import javax.net.ssl.SSLContext;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Conexión propia de M1 a Postventa; no sustituye el ConnectionFactory de Productos. */
public final class ConexionEventosPedido implements AutoCloseable {

    private final CachingConnectionFactory conexion;
    private final RabbitTemplate plantilla;

    ConexionEventosPedido(String url) {
        conexion = crearConexion(url);
        conexion.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        conexion.setPublisherReturns(true);
        plantilla = new RabbitTemplate(conexion);
        plantilla.setMandatory(true);
    }

    private static CachingConnectionFactory crearConexion(String url) {
        try {
            URI destino = URI.create(url);
            var cliente = new com.rabbitmq.client.ConnectionFactory();
            if ("amqps".equals(destino.getScheme())) {
                // Cargar credenciales/vhost sin invocar el TLS permisivo de setUri(amqps).
                cliente.setUri("amqp" + url.substring("amqps".length()));
                if (destino.getPort() == -1) {
                    cliente.setPort(com.rabbitmq.client.ConnectionFactory.DEFAULT_AMQP_OVER_SSL_PORT);
                }
                cliente.useSslProtocol(SSLContext.getDefault());
                cliente.enableHostnameVerification();
            } else {
                cliente.setUri(url);
            }
            cliente.setConnectionTimeout(10000);
            return new CachingConnectionFactory(cliente);
        } catch (Exception e) {
            // No incluir la URI ni la causa: pueden contener credenciales de CloudAMQP.
            throw new IllegalArgumentException("No se pudo configurar la conexion AMQP de eventos");
        }
    }

    RabbitTemplate plantilla() {
        return plantilla;
    }

    @Override
    public void close() {
        plantilla.destroy();
        conexion.destroy();
    }
}

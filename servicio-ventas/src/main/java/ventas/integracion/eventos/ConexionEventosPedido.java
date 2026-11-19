package ventas.integracion.eventos;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Conexión propia de M1 a Postventa; no sustituye el ConnectionFactory de Productos. */
public final class ConexionEventosPedido implements AutoCloseable {

    private final CachingConnectionFactory conexion;
    private final RabbitTemplate plantilla;

    ConexionEventosPedido(String url) {
        conexion = new CachingConnectionFactory();
        try {
            conexion.setUri(url);
            conexion.setConnectionTimeout(10000);
            conexion.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            conexion.setPublisherReturns(true);
            plantilla = new RabbitTemplate(conexion);
            plantilla.setMandatory(true);
        } catch (Exception e) {
            conexion.destroy();
            // No incluir la URI ni la causa: pueden contener credenciales de CloudAMQP.
            throw new IllegalArgumentException("EVENTOS_RABBITMQ_URL debe ser una URL AMQP valida");
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

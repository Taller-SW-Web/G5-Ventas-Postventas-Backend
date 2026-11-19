package ventas.integracion.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

class ConexionEventosPedidoTlsTest {
    @Test
    void amqpsValidaCertificadoYNombreDelServidorSinAbrirConexion() throws Exception {
        try (var eventos = new ConexionEventosPedido("amqps://guest:guest@localhost/eventos")) {
            var cache = (CachingConnectionFactory) eventos.plantilla().getConnectionFactory();
            var cliente = cache.getRabbitConnectionFactory();
            assertThat(cliente.isSSL()).isTrue();
            assertThat(cliente.getPort()).isEqualTo(5671);
            assertThat(cliente.getSocketFactory().getClass())
                    .isEqualTo(SSLContext.getDefault().getSocketFactory().getClass());
            try (var socket = (SSLSocket) cliente.getSocketFactory().createSocket()) {
                cliente.getSocketConfigurator().configure(socket);
                assertThat(socket.getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
            }
        }
    }

    @Test
    void conservaPuertoExplicitoYVhostAlUsarTls() {
        try (var eventos = new ConexionEventosPedido("amqps://guest:guest@localhost:15671/eventos%2Fprueba")) {
            var cliente = ((CachingConnectionFactory) eventos.plantilla().getConnectionFactory()).getRabbitConnectionFactory();
            assertThat(cliente.getPort()).isEqualTo(15671);
            assertThat(cliente.getVirtualHost()).isEqualTo("eventos/prueba");
        }
    }

    @Test
    void amqpLocalConservaSuPuertoSinTls() {
        try (var eventos = new ConexionEventosPedido("amqp://guest:guest@localhost/eventos")) {
            var cliente = ((CachingConnectionFactory) eventos.plantilla().getConnectionFactory()).getRabbitConnectionFactory();
            assertThat(cliente.isSSL()).isFalse();
            assertThat(cliente.getPort()).isEqualTo(5672);
        }
    }
}

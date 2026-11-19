package ventas.integracion.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EventosPedidoRabbitConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(EventosPedidoRabbitConfig.class);

    @Test
    void cuponesYConsumidorDeProductosConservanSuConexionConEventosActivados() {
        contexto.withUserConfiguration(ventas.integracion.CuponesClient.class,
                        ventas.integracion.ConsumidorProductos.class)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(org.springframework.web.client.RestClient.Builder.class, org.springframework.web.client.RestClient::builder)
                .withBean(ventas.integracion.TokenServicio.class, () -> org.mockito.Mockito.mock(ventas.integracion.TokenServicio.class))
                .withBean(ventas.pedido.MensajesProductosService.class,
                        () -> org.mockito.Mockito.mock(ventas.pedido.MensajesProductosService.class))
                .withPropertyValues("integracion.eventos.asincronos=true", "integracion.simulado=false",
                        "integracion.productos-transporte=rabbit", "spring.rabbitmq.listener.simple.auto-startup=false",
                        "integracion.eventos.rabbitmq-url=amqp://guest:guest@localhost:5672/eventos")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(ventas.integracion.ConsumidorProductos.class);
                    var productos = ctx.getBean(ConnectionFactory.class);
                    var plantillaProductos = ctx.getBean(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
                    var cliente = ctx.getBean(ventas.integracion.CuponesClient.class);
                    var proveedor = (org.springframework.beans.factory.ObjectProvider<?>)
                            org.springframework.test.util.ReflectionTestUtils.getField(cliente, "rabbit");
                    assertThat(proveedor.getObject()).isSameAs(plantillaProductos);
                    assertThat(plantillaProductos.getConnectionFactory()).isSameAs(productos);
                    var registro = ctx.getBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);
                    assertThat(registro.getListenerContainers()).hasSize(1);
                    var contenedor = (org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer)
                            registro.getListenerContainers().iterator().next();
                    assertThat(contenedor.getConnectionFactory()).isSameAs(productos);
                    assertThat(contenedor.isRunning()).isFalse();
                    assertThat(ctx.getBean(ConexionEventosPedido.class).plantilla().getConnectionFactory())
                            .isNotSameAs(productos);
                });
    }
    @Test
    void desactivadoPorDefectoConservaLaConexionDeProductos() {
        contexto.run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(ConexionEventosPedido.class);
            assertThat(ctx).hasSingleBean(ConnectionFactory.class);
        });
    }

    @Test
    void activadoSinUrlFallaConUnMensajeExplicito() {
        contexto.withPropertyValues("integracion.eventos.asincronos=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                    "Configura EVENTOS_RABBITMQ_URL con una URL amqp o amqps para activar los eventos");
        });
    }

    @Test
    void rechazaUrlHttpSinExponerCredenciales() {
        contexto.withPropertyValues("integracion.eventos.asincronos=true",
                "integracion.eventos.rabbitmq-url=https://usuario:secreto@example.test/vhost")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    var registro = new java.io.StringWriter();
                    ctx.getStartupFailure().printStackTrace(new java.io.PrintWriter(registro));
                    assertThat(registro.toString()).doesNotContain("usuario:secreto");
                });
    }

    @Test
    void conexionDeEventosEsIndependienteYExigeEnrutamiento() {
        contexto.withPropertyValues("integracion.eventos.asincronos=true",
                "integracion.eventos.rabbitmq-url=amqp://guest:guest@localhost:5672/eventos")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(ConexionEventosPedido.class);
                    ConnectionFactory productos = ctx.getBean(ConnectionFactory.class);
                    ConexionEventosPedido eventos = ctx.getBean(ConexionEventosPedido.class);
                    assertThat(eventos.plantilla().getConnectionFactory()).isNotSameAs(productos);
                    assertThat(eventos.plantilla().isMandatoryFor(null)).isTrue();
                    assertThat(eventos.plantilla().getConnectionFactory().isPublisherConfirms()).isTrue();
                    assertThat(eventos.plantilla().getConnectionFactory().isPublisherReturns()).isTrue();
                });
    }
}

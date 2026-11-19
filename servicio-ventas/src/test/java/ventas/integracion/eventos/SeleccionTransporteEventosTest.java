package ventas.integracion.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import ventas.integracion.PostventaClient;
import ventas.integracion.PostventaClient.Envio;

class SeleccionTransporteEventosTest {
    private final PostventaClient http = mock(PostventaClient.class);
    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(EventosPedidoRabbitConfig.class, PublicadorEventosRabbit.class,
                    EnvioEventosHttp.class, EnvioEventosScheduler.class)
            .withBean(PostventaClient.class, () -> http)
            .withBean(OutboxEventoRepository.class, () -> mock(OutboxEventoRepository.class));

    @Test
    void porDefectoSigueUsandoHttpSinConexionDeEventos() {
        contexto.withPropertyValues("integracion.postventa-simulado=false").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(TransporteEventosPedido.class)
                    .hasSingleBean(EnvioEventosHttp.class).hasSingleBean(EnvioEventosScheduler.class)
                    .doesNotHaveBean(PublicadorEventosRabbit.class).doesNotHaveBean(ConexionEventosPedido.class);
            var evento = new OutboxEvento();
            evento.setIdEvento(UUID.randomUUID());
            evento.setPayload("{}");
            when(http.enviarEvento(evento.getIdEvento(), "{}")).thenReturn(Envio.ACEPTADO);
            assertThat(ctx.getBean(TransporteEventosPedido.class).enviar(evento)).isEqualTo(Envio.ACEPTADO);
            verify(http).enviarEvento(evento.getIdEvento(), "{}");
        });
    }

    @Test
    void activadoSeleccionaSoloRabbitSinSustituirLaPlantillaDeProductos() {
        contexto.withPropertyValues("integracion.eventos.asincronos=true", "integracion.postventa-simulado=false",
                "integracion.eventos.rabbitmq-url=amqp://guest:guest@localhost:5672/eventos")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(TransporteEventosPedido.class)
                            .hasSingleBean(PublicadorEventosRabbit.class).doesNotHaveBean(EnvioEventosHttp.class);
                    var productos = ctx.getBean(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
                    var eventos = ctx.getBean(ConexionEventosPedido.class).plantilla();
                    assertThat(productos).isNotSameAs(eventos);
                    assertThat(productos.getConnectionFactory()).isNotSameAs(eventos.getConnectionFactory());
                });
    }

    @Test
    void simuladoNoCreaElSchedulerAunqueRabbitEsteActivado() {
        contexto.withPropertyValues("integracion.eventos.asincronos=true", "integracion.postventa-simulado=true",
                "integracion.eventos.rabbitmq-url=amqp://guest:guest@localhost:5672/eventos")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(EnvioEventosScheduler.class));
    }
}

package ventas.integracion.eventos;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import ventas.integracion.PostventaClient.Envio;

class PublicadorEventosRabbitTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final RabbitAdmin admin = mock(RabbitAdmin.class);
    private final PublicadorEventosRabbit publicador = new PublicadorEventosRabbit(rabbit, admin, 100);

    @ParameterizedTest
    @EnumSource(TipoEvento.class)
    void publicaJsonOriginalPersistenteConLaRutaYMetadatosAcordados(TipoEvento tipo) {
        OutboxEvento evento = evento(tipo);
        doAnswer(inv -> {
            Message m = inv.getArgument(2);
            CorrelationData c = inv.getArgument(3);
            assertThat(m.getBody()).isEqualTo(evento.getPayload().getBytes(UTF_8));
            assertThat(m.getMessageProperties().getMessageId()).isEqualTo(evento.getIdEvento().toString());
            assertThat(m.getMessageProperties().getType()).isEqualTo(tipo.getValor());
            assertThat(m.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat(m.getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
            assertThat(m.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            c.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq("ventas.eventos"), eq(tipo.getValor()), any(Message.class), any(CorrelationData.class));
        assertThat(publicador.enviar(evento)).isEqualTo(Envio.ACEPTADO);
        verify(admin).declareExchange(org.mockito.ArgumentMatchers.argThat(e ->
                e instanceof TopicExchange && e.getName().equals("ventas.eventos") && e.isDurable() && !e.isAutoDelete()));
    }

    @Test
    void ackConMensajeDevueltoNoSeAcepta() {
        responder(true, true);
        assertThat(publicador.enviar(evento(TipoEvento.PEDIDO_PAGADO))).isEqualTo(Envio.NO_DISPONIBLE);
    }

    @Test
    void nackNoSeAcepta() {
        responder(false, false);
        assertThat(publicador.enviar(evento(TipoEvento.PEDIDO_PAGADO))).isEqualTo(Envio.NO_DISPONIBLE);
    }

    @Test
    void sinConfirmacionQuedaPendiente() {
        assertThat(publicador.enviar(evento(TipoEvento.PEDIDO_PAGADO))).isEqualTo(Envio.NO_DISPONIBLE);
    }

    @Test
    void brokerCaidoNoSeAcepta() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("caido")))
                .when(admin).declareExchange(any());
        assertThat(publicador.enviar(evento(TipoEvento.PEDIDO_PAGADO))).isEqualTo(Envio.NO_DISPONIBLE);
    }

    @Test
    void outboxSeGuardaSoloDespuesDelConfirmYElReintentoConservaElUuid() throws Exception {
        OutboxEvento evento = evento(TipoEvento.PEDIDO_PAGADO);
        OutboxEventoRepository outbox = mock(OutboxEventoRepository.class);
        org.mockito.Mockito.when(outbox.findTop50ByEnviadoEnIsNullAndIntentosLessThanOrderByCreadoEnAsc(10))
                .thenReturn(List.of(evento));
        CountDownLatch enviado = new CountDownLatch(1);
        AtomicReference<CorrelationData> correlacion = new AtomicReference<>();
        doAnswer(inv -> {
            correlacion.set(inv.getArgument(3));
            enviado.countDown();
            return null;
        }).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
        var scheduler = new EnvioEventosScheduler(outbox, new PublicadorEventosRabbit(rabbit, admin, 3000));
        var ejecucion = CompletableFuture.runAsync(scheduler::enviarPendientes);
        try {
            assertThat(enviado.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(evento.getEnviadoEn()).isNull();
            verify(outbox, org.mockito.Mockito.never()).save(any());
            correlacion.get().getFuture().complete(new CorrelationData.Confirm(false, "rechazado"));
            ejecucion.get(2, TimeUnit.SECONDS);
            assertThat(evento.getEnviadoEn()).isNull();
            assertThat(evento.getIntentos()).isEqualTo(1);
            responder(true, false);
            scheduler.enviarPendientes();
            assertThat(evento.getEnviadoEn()).isNotNull();
            assertThat(evento.getIdEvento().toString()).isEqualTo(correlacion.get().getId());
        } finally {
            if (correlacion.get() != null) {
                correlacion.get().getFuture().complete(new CorrelationData.Confirm(false, "fin de prueba"));
            }
            ejecucion.get(4, TimeUnit.SECONDS);
        }
    }

    private void responder(boolean ack, boolean devuelto) {
        doAnswer(inv -> {
            CorrelationData c = inv.getArgument(3);
            if (devuelto) {
                c.setReturned(new ReturnedMessage(inv.getArgument(2), 312, "NO_ROUTE", "ventas.eventos", "pedido.pagado"));
            }
            c.getFuture().complete(new CorrelationData.Confirm(ack, null));
            return null;
        }).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    private OutboxEvento evento(TipoEvento tipo) {
        OutboxEvento evento = new OutboxEvento();
        evento.setIdEvento(UUID.randomUUID());
        evento.setTipo(tipo);
        evento.setCreadoEn(OffsetDateTime.now());
        evento.setPayload("{\"idEvento\":\"" + evento.getIdEvento() + "\",\"tipo\":\"" + tipo.getValor()
                + "\",\"datos\":{\"canal\":\"MARKETPLACE\",\"comentario\":\"envío\"}}");
        return evento;
    }

    @Test
    void elExchangeSeDeclaraUnaVezYSeRedeclaraTrasUnFallo() {
        responder(true, false);
        publicador.enviar(evento(TipoEvento.PEDIDO_PAGADO));
        publicador.enviar(evento(TipoEvento.PEDIDO_ANULADO));
        org.mockito.Mockito.verify(admin, org.mockito.Mockito.times(1)).declareExchange(any());

        org.mockito.Mockito.reset(rabbit);   // el broker deja de confirmar
        assertThat(publicador.enviar(evento(TipoEvento.PEDIDO_ENTREGADO))).isEqualTo(Envio.NO_DISPONIBLE);
        responder(true, false);
        publicador.enviar(evento(TipoEvento.PEDIDO_PAGADO));
        org.mockito.Mockito.verify(admin, org.mockito.Mockito.times(2)).declareExchange(any());
    }
}

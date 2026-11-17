package postventa.integracion.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;

import postventa.compartido.CanalVenta;
import postventa.compartido.ConflictoException;
import postventa.compartido.SolicitudInvalidaException;

/**
 * Decisiones del consumidor de la cola postventa.eventos-pedido.q sin broker ni base de datos.
 * Lo que va a la DLQ es lo que lanza AmqpRejectAndDontRequeueException (sin reintentos) o lo que sigue
 * fallando al agotar los reintentos (EventosRabbitConfigTest).
 */
@ExtendWith(MockitoExtension.class)
class ConsumidorEventosPedidoTest {

    private static final UUID ID_EVENTO = UUID.fromString("3f1c2a9e-7b4d-4e8a-9c21-5d6f7a8b9c0d");

    /** Mismo JSON que guarda M1 en outbox_evento.payload (acuerdos-mecanismo-asincrono.md, sección 2). */
    private static final String PAGADO = """
            {
              "idEvento": "3f1c2a9e-7b4d-4e8a-9c21-5d6f7a8b9c0d",
              "tipo": "pedido.pagado",
              "pedidoId": "PED-2026-000123",
              "fecha": "2026-11-17T15:00:00Z",
              "datos": {
                "canal": "MARKETPLACE",
                "clienteId": "11111111-1111-1111-1111-111111111111",
                "vendedorId": null,
                "total": 150.00,
                "moneda": "PEN",
                "motivo": null,
                "items": [ { "productoId": "PROD-001", "cantidad": 2, "importe": 75.00 } ],
                "devolucionId": null
              }
            }
            """;

    @Mock
    private EventoPedidoService servicio;

    private ConsumidorEventosPedido consumidor;

    @BeforeEach
    void preparar() {
        consumidor = new ConsumidorEventosPedido(servicio, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void eventoValidoSeEntregaAlServicioConTodosSusDatos() {
        when(servicio.recibir(any())).thenReturn(true);

        consumidor.recibir(mensaje(PAGADO));

        ArgumentCaptor<EventoPedidoPayload> captor = ArgumentCaptor.forClass(EventoPedidoPayload.class);
        verify(servicio).recibir(captor.capture());
        EventoPedidoPayload evento = captor.getValue();
        assertThat(evento.idEvento()).isEqualTo(ID_EVENTO);
        assertThat(evento.tipo()).isEqualTo("pedido.pagado");
        assertThat(evento.pedidoId()).isEqualTo("PED-2026-000123");
        assertThat(evento.fecha()).isEqualTo(OffsetDateTime.parse("2026-11-17T15:00:00Z"));
        assertThat(evento.datos().canal()).isEqualTo(CanalVenta.MARKETPLACE);
        assertThat(evento.datos().items()).hasSize(1);
    }

    @Test
    void eventoRepetidoSeConfirmaSinError() {
        when(servicio.recibir(any())).thenReturn(false);   // ya registrado: el servicio no lo vuelve a aplicar

        assertThatCode(() -> consumidor.recibir(mensaje(PAGADO))).doesNotThrowAnyException();
        verify(servicio).recibir(any());
    }

    @Test
    void mensajeQueNoEsJsonVaALaDlqSinLlegarAlServicio() {
        assertThatThrownBy(() -> consumidor.recibir(mensaje("esto no es json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(servicio);
    }

    @Test
    void mensajeVacioVaALaDlq() {
        assertThatThrownBy(() -> consumidor.recibir(mensaje("null")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(servicio);
    }

    @Test
    void eventoQueNoPasaLaValidacionVaALaDlqSinReintentos() {
        when(servicio.recibir(any())).thenThrow(new SolicitudInvalidaException("SOLICITUD_INVALIDA", "falta el motivo"));

        assertThatThrownBy(() -> consumidor.recibir(mensaje(PAGADO)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void eventoEnConflictoVaALaDlqSinReintentos() {
        when(servicio.recibir(any())).thenThrow(new ConflictoException("CONFLICTO", "estado no permitido"));

        assertThatThrownBy(() -> consumidor.recibir(mensaje(PAGADO)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(ConflictoException.class);
    }

    @Test
    void errorTemporalSePropagaParaQueSeReintente() {
        DataAccessResourceFailureException caida = new DataAccessResourceFailureException("base no disponible");
        when(servicio.recibir(any())).thenThrow(caida);

        assertThatThrownBy(() -> consumidor.recibir(mensaje(PAGADO)))
                .isSameAs(caida)
                .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    private static Message mensaje(String cuerpo) {
        MessageProperties propiedades = new MessageProperties();
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(cuerpo.getBytes(StandardCharsets.UTF_8), propiedades);
    }
}

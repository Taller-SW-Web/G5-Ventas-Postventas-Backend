package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import ventas.pedido.MensajesProductosService;
import ventas.pedido.MensajesProductosService.Desenlace;

class ConsumidorProductosTest {

    /** Ejemplo con el sobre de asyncapi.yaml (MessageEnvelope). */
    static final String CREADA = """
            {"message_id":"m-1","schema_version":1,"occurred_at":"2026-10-12T15:00:00Z",
             "correlation_id":"c-1","operation_id":"op-1","producer":"inventory-svc","kind":"event",
             "name":"inventory.reservation.created",
             "data":{"order_id":"PED-2026-00981","reservation_id":"RES-778"}}""";

    final MensajesProductosService servicio = mock(MensajesProductosService.class);
    final ConsumidorProductos consumidor = new ConsumidorProductos(servicio, new ObjectMapper());

    private static Message mensaje(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test
    void leeElSobreYSeLoPasaAlServicio() {
        when(servicio.procesar(any())).thenReturn(Desenlace.APLICADO);

        consumidor.recibir(mensaje(CREADA));

        ArgumentCaptor<MensajeInventario> captor = ArgumentCaptor.forClass(MensajeInventario.class);
        verify(servicio).procesar(captor.capture());
        MensajeInventario m = captor.getValue();
        assertThat(m.messageId()).isEqualTo("m-1");
        assertThat(m.name()).isEqualTo(MensajeInventario.RESERVA_CREADA);
        assertThat(m.operationId()).isEqualTo("op-1");
        assertThat(m.pedidoId()).isEqualTo("PED-2026-00981");
        assertThat(m.reservaId()).isEqualTo("RES-778");
    }

    @Test
    void siElPedidoTodaviaNoExisteFallaParaQueSeReintente() {
        when(servicio.procesar(any())).thenReturn(Desenlace.PEDIDO_NO_ENCONTRADO);

        assertThatThrownBy(() -> consumidor.recibir(mensaje(CREADA))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mensajeQueNoEsJsonSeRechazaSinReintentar() {
        assertThatThrownBy(() -> consumidor.recibir(mensaje("no es json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(servicio);
    }

    @Test
    void mensajeSinMessageIdSeRechazaSinReintentar() {
        assertThatThrownBy(() -> consumidor.recibir(mensaje("{\"name\":\"inventory.reservation.created\",\"data\":{}}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(servicio);
    }
}

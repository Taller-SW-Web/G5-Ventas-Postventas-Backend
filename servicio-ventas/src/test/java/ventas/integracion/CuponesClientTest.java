package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

class CuponesClientTest {

    private static final UUID CLIENTE = UUID.fromString("3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a");
    private final ObjectMapper json = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RabbitTemplate> proveedor(RabbitTemplate rabbit) {
        ObjectProvider<RabbitTemplate> p = mock(ObjectProvider.class);
        when(p.getObject()).thenReturn(rabbit);
        return p;
    }

    @Test
    void publicaElConsumoEnPoCommandsConElSobreDelAsyncapi() throws Exception {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        CuponesClient cliente = new CuponesClient(false, "po.commands.x", proveedor(rabbit), json);

        assertThat(cliente.consumir("PED-2026-00981", "DESC10", CLIENTE, "MARKETPLACE",
                OffsetDateTime.parse("2026-10-13T10:00:00-05:00"))).isEmpty();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("po.commands.x"), eq("promotions.coupon.consumption.requested"), captor.capture());
        JsonNode sobre = json.readTree(captor.getValue().getBody());
        assertThat(sobre.get("name").asText()).isEqualTo("promotions.coupon.consumption.requested");
        assertThat(sobre.get("producer").asText()).isEqualTo("modulo-ventas");
        assertThat(sobre.get("kind").asText()).isEqualTo("command");
        assertThat(sobre.get("schema_version").asInt()).isEqualTo(1);
        assertThat(sobre.get("correlation_id").asText()).isEqualTo("PED-2026-00981");
        assertThat(sobre.get("operation_id").asText()).isEqualTo(CuponesClient.operacionConsumo("PED-2026-00981"));
        assertThat(UUID.fromString(sobre.get("message_id").asText())).isNotNull();
        assertThat(captor.getValue().getMessageProperties().getMessageId()).isEqualTo(sobre.get("message_id").asText());
        JsonNode data = sobre.get("data");
        assertThat(data.get("order_id").asText()).isEqualTo("PED-2026-00981");
        assertThat(data.get("coupon_code").asText()).isEqualTo("DESC10");
        assertThat(data.get("customer_ref").asText()).isEqualTo(CLIENTE.toString());
        assertThat(data.get("channel_id").asText()).isEqualTo("MARKETPLACE");
        assertThat(data.get("effective_at").asText()).isEqualTo("2026-10-13T15:00Z");
    }

    @Test
    void publicaLaRestauracionConElMotivo() throws Exception {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        new CuponesClient(false, "po.commands.x", proveedor(rabbit), json).restaurar("PED-2026-00981", "ARREPENTIMIENTO_COMPRA");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("po.commands.x"), eq("promotions.coupon.restoration.requested"), captor.capture());
        JsonNode data = json.readTree(captor.getValue().getBody()).get("data");
        assertThat(data.get("order_id").asText()).isEqualTo("PED-2026-00981");
        assertThat(data.get("cancellation_reason").asText()).isEqualTo("ARREPENTIMIENTO_COMPRA");
        assertThat(data.hasNonNull("cancelled_at")).isTrue();
    }

    @Test
    void siRabbitFallaLaRestauracionNoLanza() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        org.mockito.Mockito.doThrow(new org.springframework.amqp.AmqpConnectException(new RuntimeException("caído")))
                .when(rabbit).send(any(String.class), any(String.class), any(Message.class));
        new CuponesClient(false, "po.commands.x", proveedor(rabbit), json).restaurar("PED-2026-00981", "x");
    }

    @Test
    void laOperacionEsSiempreLaMismaParaElMismoPedido() {
        assertThat(CuponesClient.operacionConsumo("PED-2026-00981")).isEqualTo(CuponesClient.operacionConsumo("PED-2026-00981"))
                .isNotEqualTo(CuponesClient.operacionConsumo("PED-2026-00982"));
    }

    @Test
    void enModoSimuladoNoPublicaYDevuelveElCuponConsumido() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        CuponesClient cliente = new CuponesClient(true, "po.commands.x", proveedor(rabbit), json);

        MensajeInventario m = cliente.consumir("PED-2026-00981", "DESC10", CLIENTE, "CHATBOT", OffsetDateTime.now()).orElseThrow();

        assertThat(m.name()).isEqualTo(MensajeInventario.CUPON_CONSUMIDO);
        assertThat(m.pedidoId()).isEqualTo("PED-2026-00981");
        cliente.restaurar("PED-2026-00981", "x");
        verifyNoInteractions(rabbit);
    }
}

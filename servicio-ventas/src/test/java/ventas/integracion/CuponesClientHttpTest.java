package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Contrato G5-G6 H5 §7: cupones por HTTP, sin RabbitMQ. */
class CuponesClientHttpTest {

    static final String PEDIDO = "PED-2026-00981";
    static final String BASE = "http://productos";
    static final String RESERVA = "22222222-2222-4222-8222-222222222222";

    MockRestServiceServer servidor;
    RabbitTemplate rabbit;
    CuponesClient cliente;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void armar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        rabbit = mock(RabbitTemplate.class);
        ObjectProvider<RabbitTemplate> proveedor = mock(ObjectProvider.class);
        org.mockito.Mockito.when(proveedor.getObject()).thenReturn(rabbit);
        cliente = new CuponesClient(false, "http", "po.commands.x", BASE, proveedor, new ObjectMapper(), builder,
                TokenServicio.sinCredenciales());
    }

    @Test
    void consumirPostaElCuerpoH5ConLaClaveIgualAlOperationId() {
        String op = CuponesClient.operacionConsumo(PEDIDO);
        servidor.expect(requestTo(BASE + "/api/v1/cupones/consumos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", op))
                .andExpect(jsonPath("$.operation_id").value(op))
                .andExpect(jsonPath("$.order_id").value(PEDIDO))
                .andExpect(jsonPath("$.coupon_code").value("DESC10"))
                .andExpect(jsonPath("$.channel_id").value("MARKETPLACE"))
                .andExpect(jsonPath("$.reservation_id").value(RESERVA))
                .andExpect(jsonPath("$.commercial_snapshot_ref").value(PEDIDO))
                .andExpect(jsonPath("$.effective_at").value("2026-11-17T20:00Z"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(cliente.consumir(PEDIDO, "DESC10", UUID.randomUUID(), "MARKETPLACE",
                OffsetDateTime.parse("2026-11-17T15:00:00-05:00"), RESERVA)).isEmpty();
        servidor.verify();
        verifyNoInteractions(rabbit);
    }

    @Test
    void unFalloAlConsumirNoLanzaExcepcion() {
        servidor.expect(requestTo(BASE + "/api/v1/cupones/consumos")).andRespond(withServerError());
        assertThat(cliente.consumir(PEDIDO, "DESC10", null, "MARKETPLACE", OffsetDateTime.now(), RESERVA)).isEmpty();
    }

    @Test
    void restaurarReferenciaElConsumoOriginal() {
        String op = CuponesClient.operacionRestauracion(PEDIDO);
        servidor.expect(requestTo(BASE + "/api/v1/cupones/restauraciones"))
                .andExpect(header("Idempotency-Key", op))
                .andExpect(jsonPath("$.operation_id").value(op))
                .andExpect(jsonPath("$.consumption_operation_id").value(CuponesClient.operacionConsumo(PEDIDO)))
                .andExpect(jsonPath("$.cancellation_reason").value("ANULACION"))
                .andExpect(jsonPath("$.cancelled_at").exists())
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        cliente.restaurar(PEDIDO, "ANULACION");
        servidor.verify();
        verifyNoInteractions(rabbit);
    }

    @Test
    void consultarLeeElSnapshotDeLaOperacion() {
        String op = CuponesClient.operacionConsumo(PEDIDO);
        servidor.expect(requestTo(BASE + "/api/v1/cupones/operaciones/" + op + "?order_id=" + PEDIDO))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"operation_id":"%s","operation_type":"CONSUMIR_CUPON","status":"REJECTED","order_id":"%s",
                         "accepted_at":"2026-11-17T20:00:00Z","updated_at":"2026-11-17T20:00:02Z",
                         "completed_at":"2026-11-17T20:00:02Z","result":{"order_id":"%s","result_code":"CUPON_AGOTADO"}}
                        """.formatted(op, PEDIDO, PEDIDO), MediaType.APPLICATION_JSON));

        var r = cliente.consultar(op, PEDIDO).orElseThrow();
        assertThat(r.rechazada()).isTrue();
        assertThat(r.resultCode()).isEqualTo("CUPON_AGOTADO");
    }

    @Test
    void operacionDesconocidaDevuelveVacio() {
        servidor.expect(requestTo(BASE + "/api/v1/cupones/operaciones/x?order_id=" + PEDIDO))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(cliente.consultar("x", PEDIDO)).isEmpty();
    }
}

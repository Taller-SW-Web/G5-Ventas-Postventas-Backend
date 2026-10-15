package postventa.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import postventa.reembolso.EstadoReembolso;

class PasarelaClientTest {

    @Test
    void enviaElContratoDeExtornoYConvierteUnaRespuestaExitosa() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://pasarela.test");
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        servidor.expect(requestTo("http://pasarela.test/pasarela/v1/extornos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Idempotency-Key", "clave-1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"referencia\":\"REEM-00042\",\"monto\":129.90,\"moneda\":\"PEN\"}"))
                .andRespond(withSuccess("{\"idTransaccion\":\"TX-001\",\"estado\":\"EXITOSO\",\"detalle\":\"Confirmado\"}", MediaType.APPLICATION_JSON));

        PasarelaClient.ResultadoExtorno resultado = new PasarelaClient(false, builder.build())
                .solicitarExtorno("clave-1", "REEM-00042", new BigDecimal("129.90"), "PEN");

        assertThat(resultado.estado()).isEqualTo(EstadoReembolso.EXITOSO);
        assertThat(resultado.transaccionId()).isEqualTo("TX-001");
        servidor.verify();
    }

    @Test
    void conservaPendienteCuandoElResultadoNoPuedeConfirmarse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://pasarela.test");
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        servidor.expect(requestTo("http://pasarela.test/pasarela/v1/extornos"))
                .andRespond(request -> { throw new ResourceAccessException("timeout"); });

        PasarelaClient.ResultadoExtorno resultado = new PasarelaClient(false, builder.build())
                .solicitarExtorno("clave-timeout", "REEM-00042", new BigDecimal("129.90"), "PEN");

        assertThat(resultado.estado()).isEqualTo(EstadoReembolso.PENDIENTE);
        assertThat(resultado.transaccionId()).isNull();
        servidor.verify();
    }
}

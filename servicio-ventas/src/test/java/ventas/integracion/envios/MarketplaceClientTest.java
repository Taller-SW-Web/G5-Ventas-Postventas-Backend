package ventas.integracion.envios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ventas.integracion.TokenServicio;
import ventas.integracion.envios.MarketplaceClient.Envio;

class MarketplaceClientTest {

    private static final String URL = "http://marketplace.test/webhooks/envios";

    @Test
    void mandaElEventoTalCualYClasificaLaRespuesta() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        MarketplaceClient cliente = new MarketplaceClient(false, URL, builder, TokenServicio.sinCredenciales());
        String payload = "{\"eventId\":\"e1\",\"eventType\":\"ShipmentStatusUpdated\"}";
        servidor.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(payload))
                .andRespond(withSuccess());
        servidor.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        servidor.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(cliente.notificar(UUID.randomUUID(), payload)).isEqualTo(Envio.ACEPTADO);
        assertThat(cliente.notificar(UUID.randomUUID(), payload)).isEqualTo(Envio.RECHAZADO);
        assertThat(cliente.notificar(UUID.randomUUID(), payload)).isEqualTo(Envio.NO_DISPONIBLE);
        servidor.verify();
    }

    @Test
    void enModoSimuladoNoLlama() {
        assertThat(new MarketplaceClient(true, URL, RestClient.builder(), TokenServicio.sinCredenciales())
                .notificar(UUID.randomUUID(), "{}")).isEqualTo(Envio.SIMULADO);
    }
}

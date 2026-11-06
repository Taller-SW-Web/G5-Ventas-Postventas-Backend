package pasarela.cobro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Comprueba el pedido del token sin conectarse a Seguridad.
 * El secreto y los tokens son valores ficticios exclusivos de las pruebas.
 */
class TokenServicioTest {

    private static final String URL = "http://seguridad.test/token";
    private static final String RESPUESTA = """
            {"accessToken": "token-ficticio", "expiresIn": 3600}
            """;

    private MockRestServiceServer servidor;
    private RestClient http;
    private RelojMovible reloj;

    @BeforeEach
    void preparar() {
        RestClient.Builder constructor = RestClient.builder().baseUrl(URL);

        servidor = MockRestServiceServer.bindTo(constructor).build();
        http = constructor.build();
        reloj = new RelojMovible();
    }

    @Test
    void pideElTokenConClientCredentials() {
        servidor.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(
                        "grant_type=client_credentials"
                                + "&client_id=simulador-pasarela"
                                + "&client_secret=secreto-ficticio"))
                .andRespond(withSuccess(
                        RESPUESTA,
                        MediaType.APPLICATION_JSON));

        assertThat(servicio("secreto-ficticio").token())
                .contains("token-ficticio");

        servidor.verify();
    }

    @Test
    void reutilizaElTokenMientrasSigueVigente() {
        servidor.expect(once(), requestTo(URL))
                .andRespond(withSuccess(
                        RESPUESTA,
                        MediaType.APPLICATION_JSON));

        TokenServicio tokens = servicio("secreto-ficticio");

        tokens.token();
        reloj.avanzar(Duration.ofMinutes(58));

        assertThat(tokens.token()).contains("token-ficticio");

        servidor.verify();
    }

    @Test
    void pideOtroCuandoFaltaMenosDeUnMinutoParaVencer() {
        servidor.expect(times(2), requestTo(URL))
                .andRespond(withSuccess(
                        RESPUESTA,
                        MediaType.APPLICATION_JSON));

        TokenServicio tokens = servicio("secreto-ficticio");

        tokens.token();
        reloj.avanzar(Duration.ofMinutes(59));
        tokens.token();

        servidor.verify();
    }

    @Test
    void pideOtroDespuesDeDescartarlo() {
        servidor.expect(times(2), requestTo(URL))
                .andRespond(withSuccess(
                        RESPUESTA,
                        MediaType.APPLICATION_JSON));

        TokenServicio tokens = servicio("secreto-ficticio");

        tokens.token();
        tokens.descartar();
        tokens.token();

        servidor.verify();
    }

    @Test
    void sinSecretoNoLlamaASeguridad() {
        assertThat(servicio("").token()).isEmpty();

        servidor.verify();
    }

    @Test
    void siSeguridadFallaNoHayToken() {
        servidor.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(servicio("secreto-ficticio").token()).isEmpty();

        servidor.verify();
    }

    private TokenServicio servicio(String secreto) {
        return new TokenServicio(http, "simulador-pasarela", secreto, reloj);
    }

    private static final class RelojMovible extends Clock {

        private Instant ahora = Instant.parse("2026-11-06T12:00:00Z");

        void avanzar(Duration tiempo) {
            ahora = ahora.plus(tiempo);
        }

        @Override
        public Instant instant() {
            return ahora;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }
    }
}

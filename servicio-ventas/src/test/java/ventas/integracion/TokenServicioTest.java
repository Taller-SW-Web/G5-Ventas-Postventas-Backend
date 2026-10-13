package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

class TokenServicioTest {

    private static final String URL = "http://seguridad.test/api/v1/auth/token";
    /** Formato real de Seguridad (su openapi, /auth/token): camelCase. */
    private static final String RESPUESTA = """
            {"accessToken": "%s", "expiresIn": 3600, "scopes": ["postventa:reembolsos:procesar", "despachos:crear"]}
            """;

    static class Reloj extends Clock {
        Instant ahora = Instant.parse("2026-10-13T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    MockRestServiceServer seguridad;
    Reloj reloj;
    TokenServicio tokens;

    @BeforeEach
    void armar() {
        RestClient.Builder builder = RestClient.builder();
        seguridad = MockRestServiceServer.bindTo(builder).build();
        reloj = new Reloj();
        tokens = new TokenServicio(URL, "modulo-ventas", "secreto-de-prueba", builder, reloj);
    }

    @Test
    void pideElTokenConClientCredentialsSinScopeNiAudience() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "modulo-ventas");
        form.add("client_secret", "secreto-de-prueba");
        seguridad.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form))
                .andRespond(withSuccess(RESPUESTA.formatted("tok-1"), MediaType.APPLICATION_JSON));

        assertThat(tokens.token()).contains("tok-1");
        seguridad.verify();
    }

    @Test
    void unSoloTokenSeReusaHastaUnMinutoAntesDeQueVenza() {
        seguridad.expect(once(), requestTo(URL)).andRespond(withSuccess(RESPUESTA.formatted("tok-1"), MediaType.APPLICATION_JSON));
        seguridad.expect(once(), requestTo(URL)).andRespond(withSuccess(RESPUESTA.formatted("tok-2"), MediaType.APPLICATION_JSON));

        assertThat(tokens.token()).contains("tok-1");
        reloj.ahora = reloj.ahora.plus(Duration.ofMinutes(58));
        assertThat(tokens.token()).contains("tok-1");          // aún le quedan 2 minutos
        reloj.ahora = reloj.ahora.plus(Duration.ofSeconds(90));
        assertThat(tokens.token()).contains("tok-2");          // dentro del último minuto: pide otro
        seguridad.verify();
    }

    @Test
    void un401DelDestinoHaceQueSePidaOtro() {
        seguridad.expect(requestTo(URL)).andRespond(withSuccess(RESPUESTA.formatted("tok-1"), MediaType.APPLICATION_JSON));
        seguridad.expect(requestTo(URL)).andRespond(withSuccess(RESPUESTA.formatted("tok-2"), MediaType.APPLICATION_JSON));

        assertThat(tokens.token()).contains("tok-1");
        tokens.revisar(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "401", HttpHeaders.EMPTY, new byte[0], null));
        assertThat(tokens.token()).contains("tok-2");
    }

    @Test
    void siSeguridadFallaDevuelveVacio() {
        seguridad.expect(requestTo(URL)).andRespond(withServerError());
        assertThat(tokens.token()).isEmpty();
    }

    @Test
    void sinSecretoNoPideNada() {
        HttpHeaders cabeceras = new HttpHeaders();
        TokenServicio.sinCredenciales().autorizacion().accept(cabeceras);
        assertThat(cabeceras.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
    }

    @Test
    void autorizacionPoneElBearer() {
        seguridad.expect(requestTo(URL)).andRespond(withSuccess(RESPUESTA.formatted("tok-1"), MediaType.APPLICATION_JSON));
        HttpHeaders cabeceras = new HttpHeaders();
        tokens.autorizacion().accept(cabeceras);
        assertThat(cabeceras.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-1");
    }
}

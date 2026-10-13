package postventa.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import postventa.compartido.DependenciaNoDisponibleException;

/** Token de servicio de M2 contra un Seguridad simulado con MockRestServiceServer. */
class TokenServicioTest {

    static final String URL = "http://seguridad/api/v1/auth/token";
    static final String RESPUESTA = """
            {"accessToken":"%s","expiresIn":3600,"scopes":["ventas:pedidos:consultar"]}""";

    /** Reloj que se puede adelantar. */
    static class Reloj extends Clock {
        Instant ahora = Instant.parse("2026-10-13T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    @Test
    void pideElTokenConClientCredentialsYLoReusaMientrasEsteVigente() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        MockRestServiceServer seguridad = MockRestServiceServer.bindTo(builder).build();
        seguridad.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("grant_type=client_credentials&client_id=modulo-postventa&client_secret=secreto-de-prueba"))
                .andRespond(withSuccess(RESPUESTA.formatted("t1"), MediaType.APPLICATION_JSON));
        Reloj reloj = new Reloj();
        TokenServicio servicio = new TokenServicio(true, "modulo-postventa", "secreto-de-prueba", reloj, builder);

        assertThat(servicio.authorization()).contains("Bearer t1");
        reloj.ahora = reloj.ahora.plusSeconds(3000);
        assertThat(servicio.authorization()).contains("Bearer t1");
        seguridad.verify();
    }

    @Test
    void pideUnoNuevoUnMinutoAntesDeQueVenza() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        MockRestServiceServer seguridad = MockRestServiceServer.bindTo(builder).build();
        seguridad.expect(twice(), requestTo(URL))
                .andRespond(withSuccess(RESPUESTA.formatted("t"), MediaType.APPLICATION_JSON));
        Reloj reloj = new Reloj();
        TokenServicio servicio = new TokenServicio(true, "modulo-postventa", "secreto-de-prueba", reloj, builder);

        servicio.authorization();
        reloj.ahora = reloj.ahora.plusSeconds(3600 - 60);
        servicio.authorization();
        seguridad.verify();
    }

    @Test
    void conLaSeguridadApagadaNoPideNada() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        MockRestServiceServer seguridad = MockRestServiceServer.bindTo(builder).build();
        TokenServicio servicio = new TokenServicio(false, "modulo-postventa", "secreto-de-prueba", new Reloj(), builder);

        assertThat(servicio.authorization()).isEmpty();
        seguridad.verify();
    }

    @Test
    void siSeguridadFallaResponde503() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        MockRestServiceServer seguridad = MockRestServiceServer.bindTo(builder).build();
        seguridad.expect(requestTo(URL)).andRespond(withServerError());
        TokenServicio servicio = new TokenServicio(true, "modulo-postventa", "secreto-de-prueba", new Reloj(), builder);

        assertThatThrownBy(servicio::authorization)
                .isInstanceOf(DependenciaNoDisponibleException.class)
                .extracting("codigo").isEqualTo("SEGURIDAD_NO_DISPONIBLE");
    }
}

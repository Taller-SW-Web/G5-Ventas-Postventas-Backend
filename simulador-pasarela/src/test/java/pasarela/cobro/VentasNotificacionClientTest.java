package pasarela.cobro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import pasarela.cobro.dto.ConsultaCobroResponse;

/**
 * Comprueba el contrato HTTP sin conectarse a un servidor real.
 * El token utilizado aquí es un valor ficticio exclusivo de las pruebas.
 */
class VentasNotificacionClientTest {

    private static final String URL =
            "http://localhost:8081/api/v1/pedidos/PED-981/pagos/notificacion";

    private MockRestServiceServer servidor;
    private RestClient http;
    private VentasNotificacionClient cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder constructor = RestClient.builder()
                .baseUrl("http://localhost:8081");

        servidor = MockRestServiceServer.bindTo(constructor).build();
        http = constructor.build();

        cliente = new VentasNotificacionClient(
                http,
                "token-ficticio-pruebas");
    }

    @AfterEach
    void verificarPeticionesEsperadas() {
        servidor.verify();
    }

    @Test
    void enviaContratoDePagoYTokenYReconoce200() {
        servidor.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(
                        "Authorization",
                        "Bearer token-ficticio-pruebas"))
                .andExpect(content().json("""
                        {
                          "transaccionId": "tx-prueba-001",
                          "resultado": "APROBADO",
                          "monto": 120.50,
                          "moneda": "PEN",
                          "metodo": "TARJETA",
                          "fechaPago": "2026-10-14T14:00:02Z"
                        }
                        """))
                .andRespond(withSuccess());

        assertThat(cliente.notificar(transaccion(EstadoCobro.APROBADO)))
                .isTrue();
    }

    @Test
    void enviaRechazoSinCabeceraDeAutorizacionCuandoNoHayToken() {
        cliente = new VentasNotificacionClient(http, "");

        servidor.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().json("""
                        {
                          "transaccionId": "tx-prueba-001",
                          "resultado": "RECHAZADO"
                        }
                        """))
                .andRespond(withSuccess());

        assertThat(cliente.notificar(transaccion(EstadoCobro.RECHAZADO)))
                .isTrue();
    }

    @Test
    void respuesta503NoConfirmaLaNotificacion() {
        servidor.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(cliente.notificar(transaccion(EstadoCobro.APROBADO)))
                .isFalse();
    }

    @Test
    void respuesta401NoConfirmaLaNotificacion() {
        servidor.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(cliente.notificar(transaccion(EstadoCobro.APROBADO)))
                .isFalse();
    }

    @Test
    void respuesta204NoSustituyeEl200ExigidoPorElContrato() {
        servidor.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(cliente.notificar(transaccion(EstadoCobro.APROBADO)))
                .isFalse();
    }

    @Test
    void falloDeConexionPermiteReintentarLaNotificacion() {
        servidor.expect(requestTo(URL))
                .andRespond(withException(
                        new IOException("Conexión interrumpida")));

        assertThat(cliente.notificar(transaccion(EstadoCobro.APROBADO)))
                .isFalse();
    }

    @Test
    void transaccionEnProcesamientoNoGeneraUnaPeticionHttp() {
        assertThatThrownBy(() ->
                cliente.notificar(transaccion(EstadoCobro.PROCESANDO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "No se puede notificar un cobro que sigue procesándose");
    }

    private static ConsultaCobroResponse transaccion(EstadoCobro estado) {
        OffsetDateTime creacion =
                OffsetDateTime.parse("2026-10-14T14:00:00Z");

        return new ConsultaCobroResponse(
                "tx-prueba-001",
                "PED-981",
                new BigDecimal("120.50"),
                "PEN",
                "TARJETA",
                estado,
                false,
                1,
                creacion,
                estado == EstadoCobro.PROCESANDO
                        ? null
                        : creacion.plusSeconds(2));
    }
}

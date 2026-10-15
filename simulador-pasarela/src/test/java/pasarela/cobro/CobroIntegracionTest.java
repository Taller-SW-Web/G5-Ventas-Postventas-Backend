package pasarela.cobro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import pasarela.cobro.dto.CobroError;
import pasarela.cobro.dto.CobroRequest;
import pasarela.cobro.dto.CobroResponse;
import pasarela.cobro.dto.ConsultaCobroResponse;

/**
 * Comprueba el recorrido HTTP con la aplicación completa.
 * Utiliza almacenamiento y planificación reales, sin contactar con M1.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "pasarela.demora-ms=50",
                "pasarela.reintento-ms=10",
                "pasarela.reintentos=1"
        })
class CobroIntegracionTest {

    @Autowired
    private TestRestTemplate http;

    @MockitoBean
    private VentasNotificacionClient ventas;

    @BeforeEach
    void preparar() {
        when(ventas.notificar(any(ConsultaCobroResponse.class)))
                .thenReturn(true);
    }

    @ParameterizedTest
    @EnumSource(EscenarioCobro.class)
    void registraProcesaYPermiteConsultarElCobro(EscenarioCobro escenario) {
        CobroRequest solicitud = new CobroRequest(
                "PED-2026-00981",
                new BigDecimal("120.50"),
                "PEN",
                "TARJETA",
                escenario);

        ResponseEntity<CobroResponse> respuesta = http.postForEntity(
                "/pasarela/v1/cobros",
                solicitud,
                CobroResponse.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        CobroResponse creada = Objects.requireNonNull(respuesta.getBody());

        assertThat(creada.transaccionId()).isNotBlank();
        assertThat(creada.estado()).isEqualTo(EstadoCobro.PROCESANDO);

        EstadoCobro resultadoEsperado =
                escenario == EscenarioCobro.APROBADO
                        ? EstadoCobro.APROBADO
                        : EstadoCobro.RECHAZADO;

        // Espera la condición observable, sin depender de una pausa fija.
        await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> {
                    ResponseEntity<ConsultaCobroResponse> consulta =
                            http.getForEntity(
                                    "/pasarela/v1/transacciones/{id}",
                                    ConsultaCobroResponse.class,
                                    creada.transaccionId());

                    assertThat(consulta.getStatusCode())
                            .isEqualTo(HttpStatus.OK);

                    ConsultaCobroResponse actual =
                            Objects.requireNonNull(consulta.getBody());

                    assertThat(actual.transaccionId())
                            .isEqualTo(creada.transaccionId());
                    assertThat(actual.estado()).isEqualTo(resultadoEsperado);
                    assertThat(actual.notificadoM1()).isTrue();
                    assertThat(actual.intentosNotificacion()).isEqualTo(1);
                    assertThat(actual.fechaPago()).isNotNull();
                    assertThat(actual.monto()).isEqualByComparingTo("120.50");
                });
    }

    @Test
    void consultaDesconocidaDevuelve404() {
        ResponseEntity<CobroError> respuesta = http.getForEntity(
                "/pasarela/v1/transacciones/inexistente",
                CobroError.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        CobroError error = Objects.requireNonNull(respuesta.getBody());

        assertThat(error.codigo()).isEqualTo("TRANSACCION_NO_ENCONTRADA");
    }
}

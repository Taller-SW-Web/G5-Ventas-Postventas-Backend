package pasarela.extorno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pasarela.extorno.dto.ExtornoRequest;
import pasarela.extorno.dto.ExtornoResponse;

/**
 * Verifica idempotencia, validaciones y concurrencia del simulador.
 * No utiliza base de datos ni servicios externos.
 */
class ExtornoServiceTest {

    private ExtornoService servicio;

    @BeforeEach
    void preparar() {
        // Las pruebas generales no necesitan esperar el timeout de la demo.
        servicio = new ExtornoService(0);
    }

    @Test
    void extorno_sinEscenarioUtilizaExitoso() {
        ExtornoResponse respuesta = servicio.procesar(
                "clave-1",
                solicitud("10.00", null));

        assertThat(respuesta.estado()).isEqualTo("EXITOSO");
        assertThat(respuesta.idTransaccion()).startsWith("TX-");
        assertThat(respuesta.detalle()).isNotBlank();
    }

    @Test
    void extorno_fallidoConservaElResultadoAlRepetirse() {
        ExtornoRequest solicitud =
                solicitud("10.00", EscenarioExtorno.FALLIDO);

        ExtornoResponse primera = servicio.procesar("clave-1", solicitud);
        ExtornoResponse repetida = servicio.procesar("clave-1", solicitud);

        assertThat(primera.estado()).isEqualTo("FALLIDO");
        assertThat(repetida).isEqualTo(primera);
    }

    @Test
    void extorno_mismaClaveEImporteEquivalenteDevuelveMismoResultado() {
        ExtornoResponse primera = servicio.procesar(
                "clave-1",
                solicitud("10.0", null));

        ExtornoResponse repetida = servicio.procesar(
                "clave-1",
                solicitud("10.00", EscenarioExtorno.EXITOSO));

        // La escala decimal y el escenario por defecto no crean otro intento.
        assertThat(repetida).isEqualTo(primera);
    }

    @Test
    void extorno_mismaClaveConOtroMontoSeRechaza() {
        servicio.procesar(
                "clave-1",
                solicitud("10.00", EscenarioExtorno.EXITOSO));

        assertThatThrownBy(() -> servicio.procesar(
                "clave-1",
                solicitud("20.00", EscenarioExtorno.EXITOSO)))
                .isInstanceOf(ExtornoException.class)
                .extracting("codigo")
                .isEqualTo("CLAVE_IDEMPOTENCIA_EN_CONFLICTO");
    }

    @Test
    void extorno_claveVaciaSeRechaza() {
        assertThatThrownBy(() -> servicio.procesar(
                " ",
                solicitud("10.00", EscenarioExtorno.EXITOSO)))
                .isInstanceOf(ExtornoException.class)
                .extracting("codigo")
                .isEqualTo("CLAVE_IDEMPOTENCIA_REQUERIDA");
    }

    @Test
    void extorno_solicitudNulaSeRechaza() {
        assertThatThrownBy(() -> servicio.procesar("clave-1", null))
                .isInstanceOf(ExtornoException.class)
                .extracting("codigo")
                .isEqualTo("SOLICITUD_INVALIDA");
    }

    @Test
    void extorno_montoCeroSeRechaza() {
        assertThatThrownBy(() -> servicio.procesar(
                "clave-1",
                solicitud("0.00", EscenarioExtorno.EXITOSO)))
                .isInstanceOf(ExtornoException.class)
                .extracting("codigo")
                .isEqualTo("SOLICITUD_INVALIDA");
    }

    @Test
    void extorno_solicitudesSimultaneasCompartenUnResultado()
            throws Exception {

        int cantidadSolicitudes = 20;
        int cantidadHilos = 8;

        CountDownLatch preparados = new CountDownLatch(cantidadHilos);
        CountDownLatch inicio = new CountDownLatch(1);

        List<Future<ExtornoResponse>> pendientes = new ArrayList<>();

        ExtornoRequest solicitud =
                solicitud("10.00", EscenarioExtorno.EXITOSO);

        try (ExecutorService ejecutor =
                     Executors.newFixedThreadPool(cantidadHilos)) {

            for (int numero = 0; numero < cantidadSolicitudes; numero++) {
                pendientes.add(ejecutor.submit(() -> {
                    preparados.countDown();

                    if (!inicio.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "No se recibió la señal de inicio");
                    }

                    return servicio.procesar("clave-compartida", solicitud);
                }));
            }

            // Los primeros hilos comienzan juntos para disputar la misma clave.
            try {
                assertThat(preparados.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                inicio.countDown();
            }

            Set<ExtornoResponse> resultados = new HashSet<>();

            for (Future<ExtornoResponse> pendiente : pendientes) {
                resultados.add(pendiente.get(5, TimeUnit.SECONDS));
            }

            assertThat(resultados).hasSize(1);
            assertThat(resultados.iterator().next().estado())
                    .isEqualTo("EXITOSO");
        }
    }

    @Test
    void extorno_timeoutDemoraYPermiteRecuperarElResultado() {
        long demoraMs = 60;
        ExtornoService servicioConDemora = new ExtornoService(demoraMs);

        ExtornoRequest solicitud =
                solicitud("10.00", EscenarioExtorno.TIMEOUT);

        long inicio = System.nanoTime();

        ExtornoResponse primera =
                servicioConDemora.procesar("clave-timeout", solicitud);

        long transcurridoMs =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);

        assertThat(transcurridoMs).isGreaterThanOrEqualTo(demoraMs);
        assertThat(primera.estado()).isEqualTo("EXITOSO");

        ExtornoResponse recuperada =
                servicioConDemora.procesar("clave-timeout", solicitud);

        // Recuperar el intento conserva el identificador y el resultado.
        assertThat(recuperada).isEqualTo(primera);
    }

    private ExtornoRequest solicitud(
            String monto,
            EscenarioExtorno escenario) {

        return new ExtornoRequest(
                "REEM-00001",
                new BigDecimal(monto),
                "PEN",
                escenario);
    }
}

package pasarela.cobro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import pasarela.cobro.dto.CobroRequest;
import pasarela.cobro.dto.CobroResponse;
import pasarela.cobro.dto.ConsultaCobroResponse;

/**
 * Verifica el procesamiento y los reintentos sin realizar llamadas HTTP.
 * Las tareas programadas se ejecutan manualmente para evitar esperas.
 */
class CobroServiceTest {

    private CobroRepository repositorio;
    private VentasNotificacionClient ventas;
    private TaskScheduler planificador;
    private CobroService servicio;

    private final Queue<TareaProgramada> tareas = new ArrayDeque<>();

    @BeforeEach
    void preparar() {
        repositorio = new CobroRepository();
        ventas = mock(VentasNotificacionClient.class);
        planificador = mock(TaskScheduler.class);

        when(planificador.schedule(
                any(Runnable.class),
                any(Instant.class)))
                .thenAnswer(invocacion -> {
                    tareas.add(new TareaProgramada(
                            invocacion.getArgument(0),
                            invocacion.getArgument(1)));

                    return null;
                });

        servicio = crearServicio(3);
    }

    @Test
    void iniciarRegistraCobroPendienteYProgramaSuProcesamiento() {
        Instant antes = Instant.now();

        CobroResponse respuesta = servicio.iniciar(
                solicitud(EscenarioCobro.APROBADO));

        Instant despues = Instant.now();
        ConsultaCobroResponse consulta = consultar(respuesta);

        assertThat(respuesta.transaccionId()).isNotBlank();
        assertThat(respuesta.estado()).isEqualTo(EstadoCobro.PROCESANDO);
        assertThat(consulta.estado()).isEqualTo(EstadoCobro.PROCESANDO);
        assertThat(consulta.pedidoId()).isEqualTo("PED-981");
        assertThat(consulta.monto()).isEqualByComparingTo("120.50");
        assertThat(consulta.notificadoM1()).isFalse();
        assertThat(consulta.intentosNotificacion()).isZero();
        assertThat(consulta.fechaPago()).isNull();
        assertThat(tareas).hasSize(1);

        TareaProgramada tarea = tareas.element();

        assertThat(tarea.fecha())
                .isBetween(antes.plusMillis(2000), despues.plusMillis(2000));

        verifyNoInteractions(ventas);
    }

    @Test
    void cobroAprobadoSeProcesaYNotifica() {
        when(ventas.notificar(any(ConsultaCobroResponse.class)))
                .thenReturn(true);

        CobroResponse respuesta = servicio.iniciar(
                solicitud(EscenarioCobro.APROBADO));

        ejecutarSiguienteTarea();

        ConsultaCobroResponse consulta = consultar(respuesta);

        assertThat(consulta.estado()).isEqualTo(EstadoCobro.APROBADO);
        assertThat(consulta.notificadoM1()).isTrue();
        assertThat(consulta.intentosNotificacion()).isEqualTo(1);
        assertThat(consulta.fechaPago()).isNotNull();
        assertThat(tareas).isEmpty();

        ArgumentCaptor<ConsultaCobroResponse> captor =
                ArgumentCaptor.forClass(ConsultaCobroResponse.class);

        verify(ventas).notificar(captor.capture());

        ConsultaCobroResponse enviada = captor.getValue();

        assertThat(enviada.transaccionId())
                .isEqualTo(respuesta.transaccionId());
        assertThat(enviada.estado()).isEqualTo(EstadoCobro.APROBADO);
        assertThat(enviada.fechaPago()).isEqualTo(consulta.fechaPago());
        assertThat(enviada.intentosNotificacion()).isEqualTo(1);

        // Al realizar el envío todavía no se ha recibido la confirmación de M1.
        assertThat(enviada.notificadoM1()).isFalse();
    }

    @Test
    void cobroRechazadoTambienNotificaSuResultado() {
        when(ventas.notificar(any(ConsultaCobroResponse.class)))
                .thenReturn(true);

        CobroResponse respuesta = servicio.iniciar(
                solicitud(EscenarioCobro.RECHAZADO));

        ejecutarSiguienteTarea();

        ConsultaCobroResponse consulta = consultar(respuesta);

        assertThat(consulta.estado()).isEqualTo(EstadoCobro.RECHAZADO);
        assertThat(consulta.notificadoM1()).isTrue();
        assertThat(consulta.intentosNotificacion()).isEqualTo(1);
    }

    @Test
    void escenarioOmitidoApruebaElCobro() {
        when(ventas.notificar(any(ConsultaCobroResponse.class)))
                .thenReturn(true);

        CobroResponse respuesta = servicio.iniciar(solicitud(null));

        ejecutarSiguienteTarea();

        assertThat(consultar(respuesta).estado())
                .isEqualTo(EstadoCobro.APROBADO);
    }

    @Test
    void falloDeNotificacionReintentaConLaMismaTransaccion() {
        when(ventas.notificar(any(ConsultaCobroResponse.class)))
                .thenReturn(false, true);

        CobroResponse respuesta = servicio.iniciar(
                solicitud(EscenarioCobro.APROBADO));

        ejecutarSiguienteTarea();

        assertThat(consultar(respuesta).notificadoM1()).isFalse();
        assertThat(tareas).hasSize(1);

        ejecutarSiguienteTarea();

        ConsultaCobroResponse consulta = consultar(respuesta);

        assertThat(consulta.notificadoM1()).isTrue();
        assertThat(consulta.intentosNotificacion()).isEqualTo(2);
        assertThat(tareas).isEmpty();

        ArgumentCaptor<ConsultaCobroResponse> captor =
                ArgumentCaptor.forClass(ConsultaCobroResponse.class);

        org.mockito.Mockito.verify(ventas, org.mockito.Mockito.times(2))
                .notificar(captor.capture());

        assertThat(captor.getAllValues())
                .extracting(ConsultaCobroResponse::transaccionId)
                .containsExactly(
                        respuesta.transaccionId(),
                        respuesta.transaccionId());

        assertThat(captor.getAllValues().get(0).fechaPago())
                .isEqualTo(captor.getAllValues().get(1).fechaPago());
    }

    @Test
    void agotadosLosReintentosConservaElResultadoSinMarcarNotificado() {
        when(ventas.notificar(any(ConsultaCobroResponse.class)))
                .thenReturn(false);

        CobroResponse respuesta = servicio.iniciar(
                solicitud(EscenarioCobro.APROBADO));

        // Un envío inicial y tres reintentos adicionales.
        for (int intento = 0; intento < 4; intento++) {
            ejecutarSiguienteTarea();
        }

        ConsultaCobroResponse consulta = consultar(respuesta);

        assertThat(consulta.estado()).isEqualTo(EstadoCobro.APROBADO);
        assertThat(consulta.notificadoM1()).isFalse();
        assertThat(consulta.intentosNotificacion()).isEqualTo(4);
        assertThat(tareas).isEmpty();
    }

    @Test
    void ceroReintentosRealizaSoloElEnvioInicial() {
        servicio = crearServicio(0);

        when(ventas.notificar(any(ConsultaCobroResponse.class)))
                .thenReturn(false);

        CobroResponse respuesta = servicio.iniciar(
                solicitud(EscenarioCobro.APROBADO));

        ejecutarSiguienteTarea();

        assertThat(consultar(respuesta).intentosNotificacion()).isEqualTo(1);
        assertThat(consultar(respuesta).notificadoM1()).isFalse();
        assertThat(tareas).isEmpty();
    }

    @Test
    void transaccionDesconocidaDevuelveResultadoVacio() {
        assertThat(servicio.consultar("inexistente")).isEmpty();

        verifyNoInteractions(ventas);
    }

    private CobroService crearServicio(int reintentos) {
        return new CobroService(
                repositorio,
                ventas,
                planificador,
                2000,
                reintentos,
                1000);
    }

    private static CobroRequest solicitud(EscenarioCobro escenario) {
        return new CobroRequest(
                "PED-981",
                new BigDecimal("120.50"),
                "PEN",
                "TARJETA",
                escenario);
    }

    private ConsultaCobroResponse consultar(CobroResponse respuesta) {
        return servicio.consultar(respuesta.transaccionId())
                .orElseThrow();
    }

    private void ejecutarSiguienteTarea() {
        assertThat(tareas).isNotEmpty();
        tareas.remove().accion().run();
    }

    private record TareaProgramada(Runnable accion, Instant fecha) {
    }
}

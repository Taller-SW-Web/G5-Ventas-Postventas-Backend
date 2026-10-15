package postventa.calificacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import postventa.calificacion.dto.RegistrarCsatRequest;
import postventa.compartido.CanalVenta;
import postventa.compartido.ConflictoException;
import postventa.compartido.DependenciaNoDisponibleException;
import postventa.compartido.SolicitudInvalidaException;
import postventa.dashboard.ProyeccionesService;
import postventa.integracion.PedidoResumen;
import postventa.integracion.VentasClient;

/**
 * Prueba las reglas de F5 con sus dependencias simuladas.
 * No conecta con Supabase.
 */
@ExtendWith(MockitoExtension.class)
class CalificacionServiceTest {

    private static final UUID CLIENTE =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID OTRO_CLIENTE =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String PEDIDO = "PED-2026-00981";

    @Mock
    private CalificacionRepository calificaciones;

    @Mock
    private ProyeccionesService proyecciones;

    @Mock
    private VentasClient ventas;

    @Mock
    private PlatformTransactionManager gestor;

    private CalificacionService servicio;

    @BeforeEach
    void preparar() {
        servicio = new CalificacionService(
                calificaciones, proyecciones, ventas, gestor);
    }

    @Test
    void esp11_registroValidoGuardaCalificacionYActualizaMetricas() {
        pedidoDisponible("ENTREGADO", CLIENTE);
        transaccionDisponible();
        insercionDisponible();

        servicio.registrar(solicitud(5), CLIENTE);

        ArgumentCaptor<LocalDate> captor =
                ArgumentCaptor.forClass(LocalDate.class);

        verify(calificaciones).insertarSiNoExiste(
                eq(981L),
                eq(5),
                eq("Buena experiencia"),
                eq("MARKETPLACE"),
                captor.capture());

        assertThat(captor.getValue()).isNotNull();
        ArgumentCaptor<OffsetDateTime> fechaProyeccion =
                ArgumentCaptor.forClass(OffsetDateTime.class);

        verify(proyecciones).calificacionRegistrada(
                fechaProyeccion.capture(), eq(CanalVenta.MARKETPLACE), eq(5));

        assertThat(fechaProyeccion.getValue().toLocalDate())
                .isEqualTo(captor.getValue());
        assertThat(fechaProyeccion.getValue().getOffset()).isEqualTo(ZoneOffset.UTC);

        verify(gestor).commit(any());
        verify(gestor, never()).rollback(any());
    }

    @Test
    void esp11_clienteDelCuerpoNoPuedeSuplantarAlAutenticado() {
        assertThatThrownBy(() ->
                servicio.registrar(solicitud(5), OTRO_CLIENTE))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(ventas, calificaciones, proyecciones, gestor);
    }

    @Test
    void esp11_pedidoAjenoNoSeGuarda() {
        pedidoDisponible("ENTREGADO", OTRO_CLIENTE);

        assertThatThrownBy(() ->
                servicio.registrar(solicitud(5), CLIENTE))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(calificaciones, proyecciones, gestor);
    }

    @Test
    void esp11_pedidoNoEntregadoNoSeGuarda() {
        pedidoDisponible("DESPACHADO", CLIENTE);

        assertThatThrownBy(() ->
                servicio.registrar(solicitud(5), CLIENTE))
                .isInstanceOf(PedidoNoEntregadoException.class);

        verifyNoInteractions(calificaciones, proyecciones, gestor);
    }

    @Test
    void esp11_puntuacionInvalidaNoConsultaNiGuarda() {
        assertThatThrownBy(() ->
                servicio.registrar(solicitud(6), CLIENTE))
                .isInstanceOf(SolicitudInvalidaException.class);

        verifyNoInteractions(ventas, calificaciones, proyecciones, gestor);
    }

    @Test
    void esp11_duplicadoNoVuelveAIncrementarMetricas() {
        pedidoDisponible("ENTREGADO", CLIENTE);
        transaccionDisponible();

        assertThatThrownBy(() ->
                servicio.registrar(solicitud(5), CLIENTE))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo")
                .isEqualTo("CSAT_ALREADY_SUBMITTED");

        verifyNoInteractions(proyecciones);
        verify(gestor).rollback(any());
    }

    @Test
    void esp11_otrosErroresDeIntegridadNoSeOcultanComoDuplicados() {
        pedidoDisponible("ENTREGADO", CLIENTE);
        transaccionDisponible();

        when(calificaciones.insertarSiNoExiste(
                eq(981L), eq(5), eq("Buena experiencia"),
                eq("MARKETPLACE"), any(LocalDate.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "Fallo de integridad distinto al pedido duplicado"));

        assertThatThrownBy(() ->
                servicio.registrar(solicitud(5), CLIENTE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("Fallo de integridad distinto al pedido duplicado");

        verifyNoInteractions(proyecciones);
        verify(gestor).rollback(any());
        verify(gestor, never()).commit(any());
    }

    @Test
    void esp12_falloDeMetricasSolicitaRevertirLaTransaccion() {
        pedidoDisponible("ENTREGADO", CLIENTE);
        transaccionDisponible();
        insercionDisponible();

        doThrow(new IllegalStateException("Fallo al actualizar métricas"))
                .when(proyecciones)
                .calificacionRegistrada(
                        any(OffsetDateTime.class),
                        eq(CanalVenta.MARKETPLACE),
                        eq(5));

        assertThatThrownBy(() ->
                servicio.registrar(solicitud(5), CLIENTE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fallo al actualizar métricas");

        verify(gestor).rollback(any());
        verify(gestor, never()).commit(any());
    }

    @Test
    void rnf05_ventasCaidoNoGuardaCalificacion() {
        when(ventas.consultarPedido(PEDIDO))
                .thenThrow(new DependenciaNoDisponibleException(
                        "VENTAS_NO_DISPONIBLE",
                        "No se pudo consultar Ventas"));

        assertThatThrownBy(() ->
                servicio.registrar(solicitud(5), CLIENTE))
                .isInstanceOf(DependenciaNoDisponibleException.class);

        verifyNoInteractions(calificaciones, proyecciones, gestor);
    }

    private RegistrarCsatRequest solicitud(int puntuacion) {
        return new RegistrarCsatRequest(
                PEDIDO,
                CLIENTE,
                CanalVenta.MARKETPLACE,
                puntuacion,
                "Buena experiencia");
    }

    private void pedidoDisponible(String estado, UUID propietario) {
        PedidoResumen pedido = new PedidoResumen(
                PEDIDO, estado, propietario,
                null, null, List.of(), null);

        when(ventas.consultarPedido(PEDIDO))
                .thenReturn(Optional.of(pedido));
    }

    private void insercionDisponible() {
        when(calificaciones.insertarSiNoExiste(
                eq(981L), eq(5), eq("Buena experiencia"),
                eq("MARKETPLACE"), any(LocalDate.class)))
                .thenReturn(1);
    }

    private void transaccionDisponible() {
        when(gestor.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
    }
}

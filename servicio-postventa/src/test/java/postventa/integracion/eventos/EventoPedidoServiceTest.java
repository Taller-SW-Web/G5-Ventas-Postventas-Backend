package postventa.integracion.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import postventa.compartido.CanalVenta;
import postventa.compartido.SolicitudInvalidaException;
import postventa.dashboard.ProyeccionesService;

/**
 * Prueba las decisiones del receptor sin base de datos.
 * La atomicidad real se verificará por separado con PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class EventoPedidoServiceTest {

    @Mock
    private ValidadorEventoPedido validador;

    @Mock
    private EventoRecibidoRepository eventos;

    @Mock
    private ProyeccionesService proyecciones;

    @Mock
    private postventa.devolucion.DevolucionService devoluciones;

    private EventoPedidoService servicio;

    @BeforeEach
    void preparar() {
        servicio = new EventoPedidoService(validador, eventos, proyecciones, devoluciones);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pedido.pagado",
            "pedido.anulado",
            "pedido.entregado"
    })
    void eventoNuevoAplicaProyeccionesDespuesDeRegistrarlo(String tipo) {
        EventoPedidoPayload evento = evento(tipo);
        when(eventos.registrarSiNuevo(evento.idEvento(), tipo)).thenReturn(true);

        assertThat(servicio.recibir(evento)).isTrue();

        InOrder orden = inOrder(validador, eventos, proyecciones);
        orden.verify(validador).validar(evento);
        orden.verify(eventos).registrarSiNuevo(evento.idEvento(), tipo);
        orden.verify(proyecciones).aplicarEventoPedido(
                tipo, evento.fecha(), evento.datos().canal());
        orden.verifyNoMoreInteractions();
    }

    @Test
    void duplicadoNoVuelveAAplicarProyecciones() {
        EventoPedidoPayload evento = evento("pedido.pagado");
        when(eventos.registrarSiNuevo(evento.idEvento(), evento.tipo()))
                .thenReturn(false);

        assertThat(servicio.recibir(evento)).isFalse();

        verifyNoInteractions(proyecciones);
    }

    @Test
    void eventoInvalidoNoSeRegistraNiModificaProyecciones() {
        EventoPedidoPayload evento = evento("pedido.desconocido");
        doThrow(new SolicitudInvalidaException(
                "DATOS_INVALIDOS", "Tipo de evento no permitido"))
                .when(validador).validar(evento);

        assertThatThrownBy(() -> servicio.recibir(evento))
                .isInstanceOf(SolicitudInvalidaException.class);

        verifyNoInteractions(eventos, proyecciones);
    }

    @Test
    void falloDelRegistroNoAplicaProyecciones() {
        EventoPedidoPayload evento = evento("pedido.entregado");
        when(eventos.registrarSiNuevo(evento.idEvento(), evento.tipo()))
                .thenThrow(new DataAccessResourceFailureException(
                        "Base de datos no disponible"));

        assertThatThrownBy(() -> servicio.recibir(evento))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verifyNoInteractions(proyecciones);
    }

    @Test
    void falloDeProyeccionesSePropagaParaRevertirLaTransaccion() {
        EventoPedidoPayload evento = evento("pedido.anulado");
        when(eventos.registrarSiNuevo(evento.idEvento(), evento.tipo()))
                .thenReturn(true);

        doThrow(new IllegalStateException("Fallo de proyecciones"))
                .when(proyecciones).aplicarEventoPedido(
                        evento.tipo(), evento.fecha(), evento.datos().canal());

        assertThatThrownBy(() -> servicio.recibir(evento))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fallo de proyecciones");
    }

    @Test
    void laEntregaDeUnReemplazoVaALaDevolucionYNoAlDashboard() {
        EventoPedidoPayload evento = reemplazo("pedido.entregado", null);
        when(eventos.registrarSiNuevo(evento.idEvento(), "pedido.entregado")).thenReturn(true);

        org.assertj.core.api.Assertions.assertThat(servicio.recibir(evento)).isTrue();

        org.mockito.Mockito.verify(devoluciones).reemplazoEntregado("DEV-2026-0042", "PED-2026-01002");
        org.mockito.Mockito.verifyNoInteractions(proyecciones);
    }

    @Test
    void laAnulacionDeUnReemplazoReabreLaDevolucion() {
        EventoPedidoPayload evento = reemplazo("pedido.anulado", "RESERVA_EXPIRADA");
        when(eventos.registrarSiNuevo(evento.idEvento(), "pedido.anulado")).thenReturn(true);

        servicio.recibir(evento);

        org.mockito.Mockito.verify(devoluciones).reemplazoAnulado("DEV-2026-0042", "PED-2026-01002", "RESERVA_EXPIRADA");
        org.mockito.Mockito.verifyNoInteractions(proyecciones);
    }

    @Test
    void unAvisoRepetidoDeReemplazoNoSeAplicaDosVeces() {
        EventoPedidoPayload evento = reemplazo("pedido.entregado", null);
        when(eventos.registrarSiNuevo(evento.idEvento(), "pedido.entregado")).thenReturn(false);

        servicio.recibir(evento);

        org.mockito.Mockito.verifyNoInteractions(devoluciones, proyecciones);
    }

    private static EventoPedidoPayload reemplazo(String tipo, String motivo) {
        return new EventoPedidoPayload(UUID.randomUUID(), tipo, "PED-2026-01002",
                OffsetDateTime.parse("2026-10-16T12:00:00Z"),
                new EventoPedidoPayload.Datos(CanalVenta.MARKETPLACE, "11111111-1111-1111-1111-111111111111", null,
                        BigDecimal.ZERO, "PEN", motivo, null, "DEV-2026-0042"));
    }

    private static EventoPedidoPayload evento(String tipo) {
        return new EventoPedidoPayload(
                UUID.randomUUID(),
                tipo,
                "PED-2026-00981",
                OffsetDateTime.parse("2026-10-15T23:30:00-05:00"),
                new EventoPedidoPayload.Datos(
                        CanalVenta.MARKETPLACE,
                        "11111111-1111-1111-1111-111111111111",
                        null,
                        new BigDecimal("129.90"),
                        "PEN",
                        tipo.equals("pedido.anulado") ? "Solicitud del cliente" : null,
                        tipo.equals("pedido.pagado")
                                ? List.of(new EventoPedidoPayload.Item(
                                        "prd-013-01", 1, new BigDecimal("129.90")))
                                : null,
                        null));
    }
}
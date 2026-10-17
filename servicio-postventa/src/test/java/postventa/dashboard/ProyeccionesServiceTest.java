package postventa.dashboard;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import postventa.compartido.CanalVenta;
import postventa.compartido.SolicitudInvalidaException;
import postventa.dashboard.AgregadoMetricasRepository.Contador;

/** ESP-15: qué suma cada hecho y en qué fila (fecha UTC del hecho, canal). */
@ExtendWith(MockitoExtension.class)
class ProyeccionesServiceTest {

    @Mock AgregadoMetricasRepository agregados;
    @InjectMocks ProyeccionesService servicio;

    @Test
    void ca01_eventoPagadoSumaEnLaFechaUtcDelHecho() {
        // 23:30 en Lima ya es el día siguiente en UTC
        servicio.aplicarEventoPedido("pedido.pagado", OffsetDateTime.parse("2026-09-30T23:30:00-05:00"),
                CanalVenta.MARKETPLACE);

        verify(agregados).sumar(LocalDate.parse("2026-10-01"), CanalVenta.MARKETPLACE, Contador.PEDIDOS_PAGADOS, 1);
    }

    @Test
    void anuladoYEntregadoSumanSuContador() {
        OffsetDateTime fecha = OffsetDateTime.parse("2026-09-23T10:00:00Z");
        servicio.aplicarEventoPedido("pedido.anulado", fecha, CanalVenta.CHATBOT);
        servicio.aplicarEventoPedido("pedido.entregado", fecha, CanalVenta.RETAIL);

        verify(agregados).sumar(fecha.toLocalDate(), CanalVenta.CHATBOT, Contador.PEDIDOS_ANULADOS, 1);
        verify(agregados).sumar(fecha.toLocalDate(), CanalVenta.RETAIL, Contador.PEDIDOS_ENTREGADOS, 1);
    }

    @Test
    void tipoDeEventoDesconocidoNoSumaNada() {
        assertThatThrownBy(() -> servicio.aplicarEventoPedido("pedido.creado",
                OffsetDateTime.parse("2026-09-23T10:00:00Z"), CanalVenta.CHATBOT))
                .isInstanceOf(SolicitudInvalidaException.class);
        verifyNoInteractions(agregados);
    }

    @Test
    void soloReembolsosEnPenSuman() {
        OffsetDateTime fecha = OffsetDateTime.parse("2026-09-23T10:00:00Z");
        servicio.reembolsoExitoso(fecha, CanalVenta.MARKETPLACE, new BigDecimal("99.90"), "USD");
        verifyNoInteractions(agregados);

        servicio.reembolsoExitoso(fecha, CanalVenta.MARKETPLACE, new BigDecimal("99.90"), "PEN");
        verify(agregados).sumar(fecha.toLocalDate(), CanalVenta.MARKETPLACE, Contador.MONTO_REEMBOLSADO_PEN,
                new BigDecimal("99.90"));
    }

    @Test
    void csatFueraDeRangoNoModificaElAgregado() {
        OffsetDateTime fecha = OffsetDateTime.parse("2026-09-23T10:00:00Z");
        servicio.calificacionRegistrada(fecha, CanalVenta.CHATBOT, 6);
        verifyNoInteractions(agregados);

        servicio.calificacionRegistrada(fecha, CanalVenta.CHATBOT, 5);
        verify(agregados).sumarCsat(fecha.toLocalDate(), CanalVenta.CHATBOT, 5);
    }

    @Test
    void hechoSinCanalNoSumaNiFalla() {
        OffsetDateTime fecha = OffsetDateTime.parse("2026-09-23T10:00:00Z");
        servicio.aplicarEventoPedido("pedido.pagado", fecha, null);
        servicio.devolucionAprobada(fecha, null);
        servicio.reembolsoExitoso(fecha, null, new BigDecimal("99.90"), "PEN");
        servicio.calificacionRegistrada(fecha, null, 5);
        servicio.reclamoRegistrado(fecha, null);
        servicio.reclamoAtendido(fecha, null);

        verifyNoInteractions(agregados);
    }

    @Test
    void tipoDesconocidoSinCanalSigueSiendoInvalido() {
        assertThatThrownBy(() -> servicio.aplicarEventoPedido("pedido.creado",
                OffsetDateTime.parse("2026-09-23T10:00:00Z"), null))
                .isInstanceOf(SolicitudInvalidaException.class);
    }
}

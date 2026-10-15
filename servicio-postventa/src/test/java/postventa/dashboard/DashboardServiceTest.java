package postventa.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import postventa.dashboard.AgregadoMetricasRepository.Totales;
import postventa.dashboard.dto.MetricasResponse;
import postventa.reclamo.ReclamoService;

/** ESP-16: métricas solo desde los agregados, con filtros de periodo y canal. */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    static final LocalDate DESDE = LocalDate.parse("2026-09-01");
    static final LocalDate HASTA = LocalDate.parse("2026-09-30");

    @Mock AgregadoMetricasRepository agregados;
    @Mock ReclamoService reclamos;
    @InjectMocks DashboardService servicio;

    @Test
    void ca01_ca02_sumaElPeriodoYCanalPedidos() {
        OffsetDateTime actualizado = OffsetDateTime.parse("2026-09-30T22:00:00Z");
        when(agregados.totales(DESDE, HASTA, CanalVenta.CHATBOT)).thenReturn(
                new Totales(5420, 115, 4980, 45, new BigDecimal("5890.5"), 462, 100, actualizado));
        when(reclamos.contarPendientesSla(DESDE, HASTA, CanalVenta.CHATBOT)).thenReturn(3L);

        MetricasResponse m = servicio.metricas(DESDE, HASTA, CanalVenta.CHATBOT);

        assertThat(m.periodo()).isEqualTo(new MetricasResponse.Periodo(DESDE, HASTA));
        assertThat(m.pedidos()).isEqualTo(new MetricasResponse.Pedidos(5420, 115, 4980));
        assertThat(m.postventa().devolucionesAprobadas()).isEqualTo(45);
        assertThat(m.postventa().montoReembolsadoPEN()).isEqualByComparingTo("5890.50");
        assertThat(m.postventa().csatPromedio()).isEqualByComparingTo("4.62");
        assertThat(m.postventa().reclamosPendientesSLA()).isEqualTo(3);
        assertThat(m.ultimaActualizacion()).isEqualTo(actualizado);
    }

    @Test
    void ca03_sinEncuestasElCsatEsNull() {
        when(agregados.totales(DESDE, HASTA, null)).thenReturn(
                new Totales(0, 0, 0, 0, BigDecimal.ZERO, 0, 0, null));

        MetricasResponse m = servicio.metricas(DESDE, HASTA, null);

        assertThat(m.postventa().csatPromedio()).isNull();
        assertThat(m.ultimaActualizacion()).isNull();
    }

    @Test
    void ca04_desdePosteriorAHastaResponde400() {
        assertThatThrownBy(() -> servicio.metricas(HASTA, DESDE, null))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("RANGO_FECHAS_INVALIDO");
        verifyNoInteractions(agregados, reclamos);
    }
}

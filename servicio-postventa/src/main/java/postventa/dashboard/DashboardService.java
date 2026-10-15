package postventa.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import postventa.compartido.CanalVenta;
import postventa.compartido.SolicitudInvalidaException;
import postventa.dashboard.AgregadoMetricasRepository.Totales;
import postventa.dashboard.dto.MetricasResponse;
import postventa.reclamo.ReclamoService;

/**
 * F6 (ESP-16): métricas del dashboard (2.12) leídas solo de agregado_metricas, más el conteo de reclamos
 * pendientes de SLA. No recorre pedidos ni tablas de M1 (guía 10.5, RNF-07).
 */
@Service
public class DashboardService {

    private final AgregadoMetricasRepository agregados;
    private final ReclamoService reclamos;

    public DashboardService(AgregadoMetricasRepository agregados, ReclamoService reclamos) {
        this.agregados = agregados;
        this.reclamos = reclamos;
    }

    @Transactional(readOnly = true)
    public MetricasResponse metricas(LocalDate desde, LocalDate hasta, CanalVenta canal) {
        if (desde.isAfter(hasta)) {
            throw new SolicitudInvalidaException("RANGO_FECHAS_INVALIDO", "desde no puede ser posterior a hasta");
        }
        Totales t = agregados.totales(desde, hasta, canal);
        BigDecimal csatPromedio = t.csatCantidad() == 0 ? null
                : BigDecimal.valueOf(t.csatSuma()).divide(BigDecimal.valueOf(t.csatCantidad()), 2, RoundingMode.HALF_UP);
        BigDecimal reembolsado = (t.montoReembolsadoPen() == null ? BigDecimal.ZERO : t.montoReembolsadoPen())
                .setScale(2, RoundingMode.HALF_UP);
        return new MetricasResponse(
                new MetricasResponse.Periodo(desde, hasta),
                new MetricasResponse.Pedidos(t.pedidosPagados(), t.pedidosAnulados(), t.pedidosEntregados()),
                new MetricasResponse.Postventa(t.devolucionesAprobadas(), reembolsado, csatPromedio,
                        reclamos.contarPendientesSla(desde, hasta, canal)),
                t.ultimaActualizacion());
    }
}

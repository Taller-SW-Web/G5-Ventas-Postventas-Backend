package postventa.dashboard;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import postventa.compartido.CanalVenta;
import postventa.compartido.SecurityConfig;
import postventa.dashboard.dto.MetricasResponse;

/** 2.12: parámetros, formato de error y nombres exactos del contrato (montoReembolsadoPEN, reclamosPendientesSLA). */
@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "seguridad.habilitada=false")
class DashboardControllerTest {

    static final LocalDate DESDE = LocalDate.parse("2026-09-01");
    static final LocalDate HASTA = LocalDate.parse("2026-09-30");

    @Autowired MockMvc mvc;

    @MockitoBean DashboardService dashboardService;

    @Test
    void metricasConLosNombresDelContrato() throws Exception {
        when(dashboardService.metricas(DESDE, HASTA, CanalVenta.CHATBOT)).thenReturn(new MetricasResponse(
                new MetricasResponse.Periodo(DESDE, HASTA), new MetricasResponse.Pedidos(5420, 115, 4980),
                new MetricasResponse.Postventa(45, new BigDecimal("5890.50"), new BigDecimal("4.62"), 3), null));

        mvc.perform(get("/api/v2/dashboard/metricas")
                        .param("desde", "2026-09-01").param("hasta", "2026-09-30").param("canal", "CHATBOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo.desde").value("2026-09-01"))
                .andExpect(jsonPath("$.pedidos.total").value(5420))
                .andExpect(jsonPath("$.postventa.montoReembolsadoPEN").value(5890.50))
                .andExpect(jsonPath("$.postventa.csatPromedio").value(4.62))
                .andExpect(jsonPath("$.postventa.reclamosPendientesSLA").value(3));
    }

    @Test
    void ca04_canalFueraDelCatalogoResponde400() throws Exception {
        mvc.perform(get("/api/v2/dashboard/metricas")
                        .param("desde", "2026-09-01").param("hasta", "2026-09-30").param("canal", "WEB"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));
        verifyNoInteractions(dashboardService);
    }

    @Test
    void sinHastaRespondeParametroFaltante() throws Exception {
        mvc.perform(get("/api/v2/dashboard/metricas").param("desde", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PARAMETRO_FALTANTE"));
    }

    @Test
    void fechaMalFormadaResponde400() throws Exception {
        mvc.perform(get("/api/v2/dashboard/metricas").param("desde", "01/09/2026").param("hasta", "2026-09-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));
    }
}

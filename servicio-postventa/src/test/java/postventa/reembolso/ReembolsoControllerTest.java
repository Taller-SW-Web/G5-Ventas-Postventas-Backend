package postventa.reembolso;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import postventa.compartido.SecurityConfig;
import postventa.compartido.SolicitudInvalidaException;
import postventa.reembolso.dto.ListadoReembolsosResponse;

/** Contrato HTTP de la bandeja de reembolsos (mockup 08) con el servicio simulado. */
@WebMvcTest(ReembolsoController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "seguridad.habilitada=false")
class ReembolsoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReembolsoService servicio;

    @MockitoBean
    private ConsultaReembolsosService consulta;

    @Test
    void listaConFiltros() throws Exception {
        var fila = new ListadoReembolsosResponse.Resumen("REEM-00041", "PED-2026-00981", OrigenReembolso.ANULACION,
                "ANUL-0012", new BigDecimal("129.90"), "PEN", EstadoReembolso.EXITOSO, "TX-001",
                LocalDate.of(2026, 10, 16), 1, "Extorno aceptado", OffsetDateTime.parse("2026-10-16T15:00:00Z"));
        when(consulta.listar(EstadoReembolso.EXITOSO, OrigenReembolso.ANULACION, "PED-2026-00981",
                null, null, null, null, 0, 10))
                .thenReturn(new ListadoReembolsosResponse(List.of(fila), 0, 1, 1));

        mvc.perform(get("/api/v2/reembolsos").param("estado", "EXITOSO").param("origen", "ANULACION")
                        .param("pedidoId", "PED-2026-00981"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido[0].reembolsoId").value("REEM-00041"))
                .andExpect(jsonPath("$.contenido[0].origenId").value("ANUL-0012"))
                .andExpect(jsonPath("$.contenido[0].ultimoResultado").value("Extorno aceptado"))
                .andExpect(jsonPath("$.totalElementos").value(1));
    }

    @Test
    void paginacionInvalidaResponde400() throws Exception {
        when(consulta.listar(null, null, null, null, null, null, null, 0, 500))
                .thenThrow(new SolicitudInvalidaException("PAGINACION_INVALIDA", "tamano fuera de rango"));

        mvc.perform(get("/api/v2/reembolsos").param("tamano", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PAGINACION_INVALIDA"));
    }
}

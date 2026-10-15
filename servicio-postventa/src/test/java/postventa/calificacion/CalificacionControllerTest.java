package postventa.calificacion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import postventa.compartido.ConflictoException;
import postventa.compartido.DependenciaNoDisponibleException;
import postventa.compartido.SecurityConfig;

/**
 * Prueba el contrato HTTP de F5 con el servicio simulado.
 * No conecta con la base de datos.
 */
@WebMvcTest(CalificacionController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "seguridad.habilitada=false",
        "seguridad.cliente-prueba=11111111-1111-1111-1111-111111111111"
})
class CalificacionControllerTest {

    private static final UUID CLIENTE =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String CUERPO = """
            {
              "pedidoId": "PED-2026-00981",
              "clienteId": "11111111-1111-1111-1111-111111111111",
              "canal": "MARKETPLACE",
              "puntuacion": 5,
              "comentario": "Buena experiencia"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CalificacionService servicio;

    @Test
    void esp11_ca01_solicitudValidaResponde201() throws Exception {
        mvc.perform(post("/api/v2/csat")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isCreated());

        verify(servicio).registrar(any(), eq(CLIENTE));
    }

    @Test
    void esp11_puntuacionFueraDeRangoResponde400() throws Exception {
        String cuerpoInvalido = Objects.requireNonNull(
                CUERPO.replace(
                        "\"puntuacion\": 5",
                        "\"puntuacion\": 6"));

        mvc.perform(post("/api/v2/csat")
                        .contentType("application/json")
                        .content(cuerpoInvalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));

        verifyNoInteractions(servicio);
    }

    @Test
    void esp11_canalDesconocidoResponde400() throws Exception {
        String cuerpoInvalido = Objects.requireNonNull(
                CUERPO.replace("MARKETPLACE", "DESCONOCIDO"));

        mvc.perform(post("/api/v2/csat")
                        .contentType("application/json")
                        .content(cuerpoInvalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));

        verifyNoInteractions(servicio);
    }

    @Test
    void esp11_pedidoAjenoResponde403() throws Exception {
        doThrow(new AccessDeniedException(
                "El pedido pertenece a otro cliente"))
                .when(servicio).registrar(any(), eq(CLIENTE));

        mvc.perform(post("/api/v2/csat")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    void esp11_pedidoNoEntregadoResponde422() throws Exception {
        doThrow(new PedidoNoEntregadoException())
                .when(servicio).registrar(any(), eq(CLIENTE));

        mvc.perform(post("/api/v2/csat")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("ORDER_NOT_DELIVERED"));
    }

    @Test
    void esp11_calificacionDuplicadaResponde409() throws Exception {
        doThrow(new ConflictoException(
                "CSAT_ALREADY_SUBMITTED",
                "El pedido ya fue calificado"))
                .when(servicio).registrar(any(), eq(CLIENTE));

        mvc.perform(post("/api/v2/csat")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CSAT_ALREADY_SUBMITTED"));
    }

    @Test
    void rnf05_ventasNoDisponibleResponde503() throws Exception {
        doThrow(new DependenciaNoDisponibleException(
                "VENTAS_NO_DISPONIBLE",
                "No se pudo consultar Ventas"))
                .when(servicio).registrar(any(), eq(CLIENTE));

        mvc.perform(post("/api/v2/csat")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("VENTAS_NO_DISPONIBLE"));
    }
    @ParameterizedTest
    @ValueSource(strings = {
            "abc",
            "981",
            "DEV-2026-00981",
            "PED-2026-981",
            "PED-2026-ABCDE"
    })
    void pedidoIdMalFormadoResponde400SinInvocarServicio(String pedidoId)
            throws Exception {

        String cuerpoInvalido = Objects.requireNonNull(
                CUERPO.replace("PED-2026-00981", pedidoId));

        mvc.perform(post("/api/v2/csat")
                        .contentType("application/json")
                        .content(cuerpoInvalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));

        verifyNoInteractions(servicio);
    }
}

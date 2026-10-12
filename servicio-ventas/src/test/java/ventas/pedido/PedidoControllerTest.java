package ventas.pedido;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import ventas.compartido.SecurityConfig;

/** Sin base de datos: comprueba rutas, códigos HTTP y el formato de error del contrato. */
@WebMvcTest(PedidoController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "seguridad.habilitada=false")
class PedidoControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PedidoService pedidoService;

    @MockitoBean
    CrearPedidoService crearPedidoService;

    @Test
    void losEndpointsPendientesDeF1Responden501() throws Exception {
        esperar501(mvc.perform(post("/api/v1/pedidos/PED-2026-00001/pagos/notificacion")));
        esperar501(mvc.perform(get("/api/v1/pedidos/PED-2026-00001")));
        esperar501(mvc.perform(get("/api/v1/pedidos")));
        esperar501(mvc.perform(patch("/api/v1/pedidos/PED-2026-00001/estado")));
        esperar501(mvc.perform(post("/api/v1/pedidos/PED-2026-00001/eventos-logistica")));
    }

    @Test
    void rutaInexistenteResponde404ConFormatoDelContrato() throws Exception {
        mvc.perform(get("/api/v1/no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RUTA_NO_ENCONTRADA"))
                .andExpect(jsonPath("$.detalles").isArray());
    }

    @Test
    void metodoNoPermitidoResponde405() throws Exception {
        mvc.perform(delete("/api/v1/pedidos/PED-2026-00001"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
    }

    private static void esperar501(ResultActions resultado) throws Exception {
        resultado.andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.codigo").value("NO_IMPLEMENTADO"))
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.detalles").isArray());
    }
}

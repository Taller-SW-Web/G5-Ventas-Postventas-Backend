package ventas.pedido;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ventas.compartido.ConflictoException;
import ventas.compartido.SecurityConfig;
import ventas.pedido.dto.EventoDespachoResponse;

@WebMvcTest(DespachoEventosController.class)
@Import({SecurityConfig.class, ValidadorPedido.class})
@TestPropertySource(properties = "seguridad.habilitada=false")
class DespachoEventosControllerTest {

    private static final String URL = "/api/v1/integraciones/despachos/eventos";
    private static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    /** Ejemplo de api-ventas.md 1.5. */
    private static final String EVENTO = """
            {
              "idEvento": "evt-desp-001",
              "idPedido": "PED-2026-00981",
              "idDespacho": "DESP-00412",
              "tipo": "DESPACHO_EN_CAMINO",
              "estadoAnterior": "ASIGNADO",
              "estadoNuevo": "EN_CAMINO",
              "ocurridoEn": "2026-10-12T18:30:00Z",
              "datos": {}
            }
            """;

    @Autowired MockMvc mvc;
    @MockitoBean EventoDespachoService eventos;

    @Test
    void recibeElEventoDelContrato() throws Exception {
        when(eventos.procesar(any())).thenReturn(new EventoDespachoResponse("evt-desp-001", "PED-2026-00981",
                EstadoPedido.DESPACHADO, false, "Pedido DESPACHADO"));

        mvc.perform(post(URL).header("Idempotency-Key", CLAVE).contentType(MediaType.APPLICATION_JSON).content(EVENTO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidoId").value("PED-2026-00981"))
                .andExpect(jsonPath("$.estadoPedido").value("DESPACHADO"))
                .andExpect(jsonPath("$.repetido").value(false));
    }

    @Test
    void sinIdempotencyKeyOConUnaQueNoEsUuidV4Responde400() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(EVENTO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("CABECERA_FALTANTE"));
        mvc.perform(post(URL).header("Idempotency-Key", "abc").contentType(MediaType.APPLICATION_JSON).content(EVENTO))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(eventos);
    }

    @Test
    void tipoDesconocidoOFaltaIdEventoResponde400() throws Exception {
        mvc.perform(post(URL).header("Idempotency-Key", CLAVE).contentType(MediaType.APPLICATION_JSON)
                        .content(EVENTO.replace("DESPACHO_EN_CAMINO", "DESPACHO_VOLANDO")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(URL).header("Idempotency-Key", CLAVE).contentType(MediaType.APPLICATION_JSON)
                        .content(EVENTO.replace("\"idEvento\": \"evt-desp-001\",", "")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(eventos);
    }

    @Test
    void transicionNoPermitidaResponde409() throws Exception {
        when(eventos.procesar(any())).thenThrow(new ConflictoException("TRANSICION_NO_PERMITIDA", "x"));
        mvc.perform(post(URL).header("Idempotency-Key", CLAVE).contentType(MediaType.APPLICATION_JSON).content(EVENTO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("TRANSICION_NO_PERMITIDA"));
    }
}

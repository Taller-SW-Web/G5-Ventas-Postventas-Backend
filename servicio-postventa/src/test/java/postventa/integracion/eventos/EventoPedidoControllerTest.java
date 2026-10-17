package postventa.integracion.eventos;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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

import postventa.compartido.SecurityConfig;
import postventa.compartido.SolicitudInvalidaException;

/** Contrato HTTP de 2.13 con el servicio simulado. */
@WebMvcTest(EventoPedidoController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "seguridad.habilitada=false")
class EventoPedidoControllerTest {

    private static final String EVENTO = """
            {
              "idEvento": "3f2c9a10-6b7d-4e21-9c55-1a2b3c4d5e6f",
              "tipo": "pedido.pagado",
              "pedidoId": "PED-2026-00981",
              "fecha": "2026-09-27T15:40:00Z",
              "datos": {
                "canal": "MARKETPLACE",
                "clienteId": "3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a",
                "total": 129.90,
                "moneda": "PEN",
                "items": [ { "productoId": "PROD-101", "cantidad": 1, "importe": 129.90 } ]
              }
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EventoPedidoService servicio;

    @Test
    void eventoNuevoResponde200() throws Exception {
        when(servicio.recibir(any())).thenReturn(true);

        mvc.perform(post("/api/v2/eventos").contentType("application/json").content(EVENTO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idEvento").value("3f2c9a10-6b7d-4e21-9c55-1a2b3c4d5e6f"))
                .andExpect(jsonPath("$.repetido").value(false));
        verify(servicio).recibir(any());
    }

    @Test
    void eventoRepetidoTambienResponde200() throws Exception {
        when(servicio.recibir(any())).thenReturn(false);

        mvc.perform(post("/api/v2/eventos").contentType("application/json").content(EVENTO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repetido").value(true));
    }

    @Test
    void eventoInvalidoResponde400() throws Exception {
        when(servicio.recibir(any())).thenThrow(new SolicitudInvalidaException("DATOS_INVALIDOS", "tipo desconocido"));

        mvc.perform(post("/api/v2/eventos").contentType("application/json").content(EVENTO.replace("pedido.pagado", "pedido.x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
    }

    @Test
    void jsonMalFormadoResponde400SinLlamarAlServicio() throws Exception {
        mvc.perform(post("/api/v2/eventos").contentType("application/json").content("{ no es json"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }
}

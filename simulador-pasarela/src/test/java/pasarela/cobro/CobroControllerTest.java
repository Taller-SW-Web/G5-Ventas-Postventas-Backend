package pasarela.cobro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pasarela.cobro.dto.CobroRequest;
import pasarela.cobro.dto.CobroResponse;
import pasarela.cobro.dto.ConsultaCobroResponse;

/**
 * Verifica las rutas, la validación y las respuestas HTTP.
 * El servicio se sustituye por un mock para evitar tareas y llamadas a M1.
 */
@WebMvcTest(CobroController.class)
@Import(CobroExceptionHandler.class)
class CobroControllerTest {

    private static final String TRANSACCION = "tx-prueba-001";

    private static final String CUERPO = """
            {
              "pedidoId": "PED-981",
              "monto": 120.50,
              "moneda": "PEN",
              "metodoPago": "TARJETA",
              "escenario": "APROBADO"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CobroService servicio;

    @Test
    void solicitudValidaResponde201ConTransaccionEnProcesamiento()
            throws Exception {

        when(servicio.iniciar(any(CobroRequest.class)))
                .thenReturn(new CobroResponse(
                        TRANSACCION,
                        EstadoCobro.PROCESANDO));

        mvc.perform(post("/pasarela/v1/cobros")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transaccionId").value(TRANSACCION))
                .andExpect(jsonPath("$.estado").value("PROCESANDO"));

        ArgumentCaptor<CobroRequest> captor =
                ArgumentCaptor.forClass(CobroRequest.class);

        verify(servicio).iniciar(captor.capture());

        CobroRequest recibida = captor.getValue();

        assertThat(recibida.pedidoId()).isEqualTo("PED-981");
        assertThat(recibida.monto()).isEqualByComparingTo("120.50");
        assertThat(recibida.moneda()).isEqualTo("PEN");
        assertThat(recibida.metodoPago()).isEqualTo("TARJETA");
        assertThat(recibida.escenario()).isEqualTo(EscenarioCobro.APROBADO);
    }

    @Test
    void transaccionExistenteResponde200ConResultadoYNotificacion()
            throws Exception {

        OffsetDateTime creacion =
                OffsetDateTime.parse("2026-10-14T14:00:00Z");

        ConsultaCobroResponse consulta = new ConsultaCobroResponse(
                TRANSACCION,
                "PED-981",
                new BigDecimal("120.50"),
                "PEN",
                "TARJETA",
                EstadoCobro.APROBADO,
                true,
                2,
                creacion,
                creacion.plusSeconds(2));

        when(servicio.consultar(TRANSACCION))
                .thenReturn(Optional.of(consulta));

        mvc.perform(get(
                        "/pasarela/v1/transacciones/{transaccionId}",
                        TRANSACCION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaccionId").value(TRANSACCION))
                .andExpect(jsonPath("$.pedidoId").value("PED-981"))
                .andExpect(jsonPath("$.estado").value("APROBADO"))
                .andExpect(jsonPath("$.notificadoM1").value(true))
                .andExpect(jsonPath("$.intentosNotificacion").value(2));
    }

    @Test
    void transaccionDesconocidaResponde404() throws Exception {
        when(servicio.consultar("inexistente"))
                .thenReturn(Optional.empty());

        mvc.perform(get(
                        "/pasarela/v1/transacciones/{transaccionId}",
                        "inexistente"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo")
                        .value("TRANSACCION_NO_ENCONTRADA"))
                .andExpect(jsonPath("$.mensaje")
                        .value("No existe la transacción indicada"));
    }

    @Test
    void montoCeroResponde400SinIniciarCobro() throws Exception {
        mvc.perform(post("/pasarela/v1/cobros")
                        .contentType("application/json")
                        .content(CUERPO.replace("120.50", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));

        verifyNoInteractions(servicio);
    }

    @Test
    void monedaNoPermitidaResponde400SinIniciarCobro() throws Exception {
        mvc.perform(post("/pasarela/v1/cobros")
                        .contentType("application/json")
                        .content(CUERPO.replace("\"PEN\"", "\"EUR\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));

        verifyNoInteractions(servicio);
    }

    @Test
    void escenarioDesconocidoResponde400SinIniciarCobro() throws Exception {
        mvc.perform(post("/pasarela/v1/cobros")
                        .contentType("application/json")
                        .content(CUERPO.replace(
                                "\"APROBADO\"",
                                "\"DESCONOCIDO\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));

        verifyNoInteractions(servicio);
    }

    @Test
    void jsonIncompletoResponde400SinIniciarCobro() throws Exception {
        mvc.perform(post("/pasarela/v1/cobros")
                        .contentType("application/json")
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));

        verifyNoInteractions(servicio);
    }
}

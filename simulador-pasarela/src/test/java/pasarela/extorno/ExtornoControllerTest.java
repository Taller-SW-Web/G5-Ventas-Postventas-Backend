package pasarela.extorno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifica el contrato HTTP con el servicio real del simulador.
 * Los registros permanecen en memoria; no utiliza Supabase ni F4.
 */
@WebMvcTest(ExtornoController.class)
@Import({
        ExtornoService.class,
        ExtornoExceptionHandler.class
})
@TestPropertySource(properties = "pasarela.extorno.timeout-ms=0")
class ExtornoControllerTest {

    private static final String RUTA = "/pasarela/v1/extornos";
    private static final String CABECERA = "X-Idempotency-Key";

    private static final String CUERPO = """
            {
              "referencia": "REEM-00001",
              "monto": 10.00,
              "moneda": "PEN"
            }
            """;

    private static final String CUERPO_FALLIDO = """
            {
              "referencia": "REEM-00001",
              "monto": 10.00,
              "moneda": "PEN",
              "escenario": "FALLIDO"
            }
            """;

    @Autowired
    private MockMvc mvc;

    private String clave;

    @BeforeEach
    void preparar() {
        // Cada prueba usa una clave propia para no compartir sus registros.
        clave = UUID.randomUUID().toString();
    }

    @Test
    void extorno_solicitudValidaResponde201() throws Exception {
        mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("EXITOSO"))
                .andExpect(jsonPath("$.idTransaccion").isNotEmpty())
                .andExpect(jsonPath("$.detalle").isNotEmpty());
    }

    @Test
    void extorno_falloDefinitivoResponde201ConEstadoFallido()
            throws Exception {

        mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(CUERPO_FALLIDO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("FALLIDO"))
                .andExpect(jsonPath("$.detalle").isNotEmpty());
    }

    @Test
    void extorno_sinCabeceraResponde400() throws Exception {
        mvc.perform(post(RUTA)
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo")
                        .value("CLAVE_IDEMPOTENCIA_REQUERIDA"));
    }

    @Test
    void extorno_montoCeroResponde400() throws Exception {
        String cuerpoInvalido = Objects.requireNonNull(
                CUERPO.replace("\"monto\": 10.00", "\"monto\": 0"));

        mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(cuerpoInvalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
    }

    @Test
    void extorno_monedaNoAdmitidaResponde400() throws Exception {
        String cuerpoInvalido = Objects.requireNonNull(
                CUERPO.replace("\"PEN\"", "\"EUR\""));

        mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(cuerpoInvalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
    }

    @Test
    void extorno_escenarioDesconocidoResponde400() throws Exception {
        String cuerpoInvalido = Objects.requireNonNull(
                CUERPO_FALLIDO.replace("\"FALLIDO\"", "\"DESCONOCIDO\""));

        mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(cuerpoInvalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));
    }

    @Test
    void extorno_mismaClaveYMismosDatosDevuelveMismaRespuesta()
            throws Exception {

        MvcResult primera = mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult repetida = mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isCreated())
                .andReturn();

        // Comprueba también que se conserve el identificador de transacción.
        assertThat(repetida.getResponse().getContentAsString())
                .isEqualTo(primera.getResponse().getContentAsString());
    }

    @Test
    void extorno_mismaClaveConOtroMontoResponde409() throws Exception {
        mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isCreated());

        String cuerpoDistinto = Objects.requireNonNull(
                CUERPO.replace("\"monto\": 10.00", "\"monto\": 20.00"));

        mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(cuerpoDistinto))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo")
                        .value("CLAVE_IDEMPOTENCIA_EN_CONFLICTO"));
    }

    @Test
    void extorno_cambiarEscenarioConLaMismaClaveResponde409()
            throws Exception {

        mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(CUERPO_FALLIDO))
                .andExpect(status().isCreated());

        mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo")
                        .value("CLAVE_IDEMPOTENCIA_EN_CONFLICTO"));
    }

    @Test
    void extorno_nuevoIntentoTrasFalloUtilizaOtraClave() throws Exception {
        mvc.perform(post(RUTA)
                        .header(CABECERA, clave)
                        .contentType("application/json")
                        .content(CUERPO_FALLIDO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("FALLIDO"));

        // F4 distingue un nuevo intento mediante otra clave de pasarela.
        mvc.perform(post(RUTA)
                        .header(CABECERA, clave + "-2")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("EXITOSO"));
    }
}

package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ventas.integracion.PostventaClient.ReembolsoRegistrado;

class PostventaClientTest {

    private static final String BASE = "http://m2.test";

    @Test
    void laClaveDeIdempotenciaEsSiempreLaMismaParaLaMismaAnulacion() {
        String clave = PostventaClient.claveIdempotencia("ANUL-0007");

        assertThat(clave).isEqualTo(PostventaClient.claveIdempotencia("ANUL-0007"));
        assertThat(clave).isNotEqualTo(PostventaClient.claveIdempotencia("ANUL-0008"));
        assertThat(UUID.fromString(clave)).isNotNull();
    }

    @Test
    void mandaElReembolsoSegunElContratoYDevuelveLoQueRespondeM2() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        servidor.expect(requestTo(BASE + "/api/v2/reembolsos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Idempotency-Key", PostventaClient.claveIdempotencia("ANUL-0012")))
                .andExpect(jsonPath("$.origen").value("ANULACION"))
                .andExpect(jsonPath("$.origenId").value("ANUL-0012"))
                .andExpect(jsonPath("$.referenciaId").value("PED-2026-00981"))
                .andExpect(jsonPath("$.monto").value(129.90))
                .andExpect(jsonPath("$.solicitadoPor").value("SISTEMA_M1"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"reembolsoId": "REEM-90812", "transaccionPasarelaId": "TX-SIM-1", "estado": "EXITOSO",
                         "monto": 129.90, "moneda": "PEN", "fechaEjecucion": "2026-09-23T00:15:30Z"}
                        """));

        Optional<ReembolsoRegistrado> r = new PostventaClient(false, BASE, builder)
                .solicitarReembolso("ANUL-0012", "PED-2026-00981", new BigDecimal("129.90"), "PEN", "Entrega fallida");

        servidor.verify();
        assertThat(r).contains(new ReembolsoRegistrado("REEM-90812", "EXITOSO"));
    }

    @Test
    void siM2FallaDevuelveVacioSinLanzar() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        servidor.expect(requestTo(BASE + "/api/v2/reembolsos")).andRespond(withServerError());

        assertThat(new PostventaClient(false, BASE, builder)
                .solicitarReembolso("ANUL-0012", "PED-2026-00981", BigDecimal.TEN, "PEN", "x")).isEmpty();
    }

    @Test
    void enModoSimuladoNoLlamaAM2() {
        assertThat(new PostventaClient(true, BASE, RestClient.builder())
                .solicitarReembolso("ANUL-0012", "PED-2026-00981", BigDecimal.TEN, "PEN", "x")).isEmpty();
    }
}

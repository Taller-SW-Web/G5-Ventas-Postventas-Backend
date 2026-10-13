package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ventas.integracion.PostventaClient.Envio;
import ventas.integracion.PostventaClient.RespuestaReembolso;

class PostventaClientTest {

    private static final String BASE = "http://m2.test";

    MockRestServiceServer servidor;
    PostventaClient cliente;

    @BeforeEach
    void armar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new PostventaClient(false, BASE, builder);
    }

    @Test
    void laClaveDeIdempotenciaEsSiempreLaMismaParaLaMismaAnulacion() {
        String clave = PostventaClient.claveIdempotencia("ANUL-0007");

        assertThat(clave).isEqualTo(PostventaClient.claveIdempotencia("ANUL-0007"));
        assertThat(clave).isNotEqualTo(PostventaClient.claveIdempotencia("ANUL-0008"));
        assertThat(UUID.fromString(clave)).isNotNull();
    }

    // ---------- 2.6 Reembolsos ----------

    @Test
    void mandaElReembolsoSegunElContratoYDevuelveLoQueRespondeM2() {
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

        RespuestaReembolso r = cliente.solicitarReembolso("ANUL-0012", "PED-2026-00981", new BigDecimal("129.90"), "PEN", "Entrega fallida");

        servidor.verify();
        assertThat(r.envio()).isEqualTo(Envio.ACEPTADO);
        assertThat(r.reembolsoId()).isEqualTo("REEM-90812");
        assertThat(r.estado()).isEqualTo("EXITOSO");
    }

    @Test
    void conflictoDeM2EsRechazadoYErrorDelServidorEsNoDisponible() {
        servidor.expect(requestTo(BASE + "/api/v2/reembolsos"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"codigo\": \"SALDO_INSUFICIENTE\"}"));
        servidor.expect(requestTo(BASE + "/api/v2/reembolsos")).andRespond(withServerError());

        assertThat(cliente.solicitarReembolso("ANUL-0012", "PED-2026-00981", BigDecimal.TEN, "PEN", "x").envio())
                .isEqualTo(Envio.RECHAZADO);
        assertThat(cliente.solicitarReembolso("ANUL-0012", "PED-2026-00981", BigDecimal.TEN, "PEN", "x").envio())
                .isEqualTo(Envio.NO_DISPONIBLE);
    }

    @Test
    void sinPermisoEsNoDisponibleParaQueSeReintente() {
        // Un 401/403 es un problema de token, no de la solicitud: no se marca como rechazada
        servidor.expect(requestTo(BASE + "/api/v2/reembolsos")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(cliente.solicitarReembolso("ANUL-0012", "PED-2026-00981", BigDecimal.TEN, "PEN", "x").envio())
                .isEqualTo(Envio.NO_DISPONIBLE);
    }

    // ---------- 2.13 Eventos ----------

    @Test
    void mandaElEventoTalCualEstaEnElOutbox() {
        String payload = "{\"idEvento\":\"3f2c9a10-6b7d-4e21-9c55-1a2b3c4d5e6f\",\"tipo\":\"pedido.pagado\"}";
        servidor.expect(requestTo(BASE + "/api/v2/eventos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(payload))
                .andRespond(withSuccess());

        assertThat(cliente.enviarEvento(UUID.fromString("3f2c9a10-6b7d-4e21-9c55-1a2b3c4d5e6f"), payload))
                .isEqualTo(Envio.ACEPTADO);
        servidor.verify();
    }

    @Test
    void eventoInvalidoEsRechazadoYM2CaidoEsNoDisponible() {
        servidor.expect(requestTo(BASE + "/api/v2/eventos")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        servidor.expect(requestTo(BASE + "/api/v2/eventos")).andRespond(withServerError());

        assertThat(cliente.enviarEvento(UUID.randomUUID(), "{}")).isEqualTo(Envio.RECHAZADO);
        assertThat(cliente.enviarEvento(UUID.randomUUID(), "{}")).isEqualTo(Envio.NO_DISPONIBLE);
    }

    @Test
    void enModoSimuladoNoLlamaAM2() {
        PostventaClient simulado = new PostventaClient(true, BASE, RestClient.builder());
        assertThat(simulado.solicitarReembolso("ANUL-0012", "PED-2026-00981", BigDecimal.TEN, "PEN", "x").envio())
                .isEqualTo(Envio.SIMULADO);
        assertThat(simulado.enviarEvento(UUID.randomUUID(), "{}")).isEqualTo(Envio.SIMULADO);
    }
}

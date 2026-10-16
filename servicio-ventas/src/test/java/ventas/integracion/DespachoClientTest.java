package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ventas.integracion.DespachoClient.Resultado;

class DespachoClientTest {

    private static final String BASE = "http://despacho.test";
    private static final MediaType PROBLEMA = MediaType.parseMediaType("application/problem+json");

    MockRestServiceServer servidor;
    DespachoClient cliente;

    @BeforeEach
    void armar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new DespachoClient(false, BASE, builder, TokenServicio.sinCredenciales());
    }

    @Test
    void mandaElCuerpoDe71ConIdempotencyKeyFijaYLeeElIdDelDespacho() {
        servidor.expect(requestTo(BASE + "/api/v1/despachos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", DespachoClient.claveIdempotencia("PED-2026-00981")))
                .andExpect(jsonPath("$.idPedido").value("PED-2026-00981"))
                .andExpect(jsonPath("$.destinatario.nombre").value("Juan Pérez"))
                .andExpect(jsonPath("$.destinatario.telefono").value("+51999888777"))
                .andExpect(jsonPath("$.destino.direccion").value("Av. La Marina 1234"))
                .andExpect(jsonPath("$.destino.referencia").value("Frente al centro comercial"))
                .andExpect(jsonPath("$.destino.departamento").value("Lima"))
                .andExpect(jsonPath("$.destino.provincia").value("Lima"))
                .andExpect(jsonPath("$.destino.distrito").value("San Miguel"))
                .andExpect(jsonPath("$.destino.codigoPostal").doesNotExist())
                .andExpect(jsonPath("$.lineas[0].sku").value("SKU-AUR-01"))
                .andExpect(jsonPath("$.lineas[0].cantidad").value(2))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"idDespacho\": \"DESP-00412\", \"estado\": \"PENDIENTE_ASIGNACION\"}"));

        DespachoClient.Respuesta r = cliente.crear(solicitud());

        servidor.verify();
        assertThat(r.resultado()).isEqualTo(Resultado.CREADO);
        assertThat(r.idDespacho()).isEqualTo("DESP-00412");
    }

    @Test
    void losOpcionalesNulosNoViajanEnElJson() {
        servidor.expect(requestTo(BASE + "/api/v1/despachos"))
                .andExpect(jsonPath("$.destino.direccion").value("Av. La Marina 1234"))
                .andExpect(jsonPath("$.destino.distrito").value("San Miguel"))
                .andExpect(jsonPath("$.destino.referencia").doesNotExist())
                .andExpect(jsonPath("$.destino.departamento").doesNotExist())
                .andExpect(jsonPath("$.destino.provincia").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"idDespacho\": \"DESP-00413\"}"));

        DespachoClient.Respuesta r = cliente.crear(new DespachoClient.Solicitud("PED-2026-00982",
                new DespachoClient.Destinatario("Juan Pérez", "+51999888777"),
                new DespachoClient.Destino("Av. La Marina 1234", null, null, null, "San Miguel"),
                List.of(new DespachoClient.Linea("SKU-AUR-01", 1))));

        servidor.verify();
        assertThat(r.idDespacho()).isEqualTo("DESP-00413");
    }

    @Test
    void la200EsQueYaTeniaDespacho() {
        servidor.expect(requestTo(BASE + "/api/v1/despachos"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"idDespacho\": \"DESP-00412\"}"));
        DespachoClient.Respuesta r = cliente.crear(solicitud());
        assertThat(r.resultado()).isEqualTo(Resultado.YA_EXISTIA);
        assertThat(r.idDespacho()).isEqualTo("DESP-00412");
    }

    @Test
    void decidePorStatusYCodeNoPorElTexto() {
        servidor.expect(requestTo(BASE + "/api/v1/despachos")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(PROBLEMA).body("{\"code\": \"DESP_ERROR_SIN_COBERTURA\", \"detail\": \"texto cualquiera\"}"));
        servidor.expect(requestTo(BASE + "/api/v1/despachos")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(PROBLEMA).body("{\"code\": \"DESP_OTRO\", \"detail\": \"sin cobertura\"}"));
        servidor.expect(requestTo(BASE + "/api/v1/despachos")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(PROBLEMA).body("{\"code\": \"DESP_PRODUCTOS_NO_DISPONIBLE\"}"));
        servidor.expect(requestTo(BASE + "/api/v1/despachos")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(cliente.crear(solicitud()).resultado()).isEqualTo(Resultado.SIN_COBERTURA);
        DespachoClient.Respuesta otro = cliente.crear(solicitud());
        assertThat(otro.resultado()).isEqualTo(Resultado.RECHAZADO);
        assertThat(otro.code()).isEqualTo("DESP_OTRO");
        assertThat(cliente.crear(solicitud()).resultado()).isEqualTo(Resultado.NO_DISPONIBLE);
        assertThat(cliente.crear(solicitud()).resultado()).isEqualTo(Resultado.NO_DISPONIBLE);
    }

    @Test
    void laClaveEsUuidV4YSiempreLaMismaParaElMismoPedido() {
        String clave = DespachoClient.claveIdempotencia("PED-2026-00981");
        assertThat(UUID.fromString(clave).version()).isEqualTo(4);
        assertThat(UUID.fromString(clave).variant()).isEqualTo(2);
        assertThat(clave).isEqualTo(DespachoClient.claveIdempotencia("PED-2026-00981"))
                .isNotEqualTo(DespachoClient.claveIdempotencia("PED-2026-00982"));
    }

    @Test
    void enModoSimuladoNoLlama() {
        DespachoClient simulado = new DespachoClient(true, BASE, RestClient.builder(), TokenServicio.sinCredenciales());
        DespachoClient.Respuesta r = simulado.crear(solicitud());
        assertThat(r.resultado()).isEqualTo(Resultado.SIMULADO);
        assertThat(r.idDespacho()).isEqualTo("DESP-SIM-PED-2026-00981");
    }

    static DespachoClient.Solicitud solicitud() {
        return new DespachoClient.Solicitud("PED-2026-00981",
                new DespachoClient.Destinatario("Juan Pérez", "+51999888777"),
                new DespachoClient.Destino("Av. La Marina 1234", "Frente al centro comercial", "Lima", "Lima", "San Miguel"),
                List.of(new DespachoClient.Linea("SKU-AUR-01", 2)));
    }
}

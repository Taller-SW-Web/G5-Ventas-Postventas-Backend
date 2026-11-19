package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ventas.compartido.DependenciaNoDisponibleException;
import ventas.integracion.ProductosClient.LineaReserva;
import ventas.integracion.ProductosClient.Operacion;

/** Contrato G5-G6 H5: consulta de resultados y de vigencia por HTTP, y location_id del almacén por entorno. */
class ProductosClientH5Test {

    static final String PEDIDO = "PED-2026-00981";
    static final String BASE = "http://productos";
    static final String LURIN = "9129b578-631e-1735-8427-b683fb524380";
    static final String RESERVA = "22222222-2222-4222-8222-222222222222";

    MockRestServiceServer servidor;
    ProductosClient cliente;

    @BeforeEach
    void armar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new ProductosClient(false, BASE, LURIN, builder, TokenServicio.sinCredenciales());
    }

    @Test
    void reservarIncluyeElAlmacenConfigurado() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, PEDIDO);
        servidor.expect(requestTo(BASE + "/api/v1/inventario/reservas"))
                .andExpect(content().json("""
                        {"order_id":"%s","operation_id":"%s","channel_id":"MARKETPLACE",
                         "lines":[{"sku":"SKU-AUR-01","quantity":1,"location_id":"%s"}]}""".formatted(PEDIDO, op, LURIN), true))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        cliente.reservar(PEDIDO, "MARKETPLACE", List.of(new LineaReserva("SKU-AUR-01", 1)));
        servidor.verify();
    }

    @Test
    void consultarOperacionLeeElResultadoDurable() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, PEDIDO);
        servidor.expect(requestTo(BASE + "/api/v1/inventario/operaciones/" + op))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"operation_id":"%s","order_id":"%s","operation_type":"RESERVAR","status":"COMPLETED",
                         "reservation_id":"%s","expires_at":"2026-11-17T20:15:00Z","result_code":null}
                        """.formatted(op, PEDIDO, RESERVA), MediaType.APPLICATION_JSON));

        var r = cliente.consultarOperacion(op).orElseThrow();
        assertThat(r.completada()).isTrue();
        assertThat(r.reservationId()).isEqualTo(RESERVA);
        assertThat(r.resultCode()).isNull();
    }

    @Test
    void operacionDesconocidaOProductosCaidoDevuelveVacio() {
        servidor.expect(requestTo(BASE + "/api/v1/inventario/operaciones/x")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        servidor.expect(requestTo(BASE + "/api/v1/inventario/operaciones/y")).andRespond(withServerError());

        assertThat(cliente.consultarOperacion("x")).get().matches(ProductosClient.ResultadoOperacion::noEncontrada);
        assertThat(cliente.consultarOperacion("y")).isEmpty();   // un 5xx nunca es éxito ni rechazo (§9)
    }

    @Test
    void consultarReservaInterpretaLaVigenciaEfectiva() {
        servidor.expect(requestTo(BASE + "/api/v1/inventario/reservas/" + RESERVA + "/estado?order_id=" + PEDIDO))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"reservation_id":"%s","order_id":"%s","estado_persistido":"ACTIVA",
                         "expires_at":"2026-11-17T20:15:00Z","observed_at":"2026-11-17T20:16:00Z",
                         "vigencia_efectiva":"VENCIDA","permite_pago":false,"permite_consumo_cupon":false}
                        """.formatted(RESERVA, PEDIDO), MediaType.APPLICATION_JSON));

        assertThat(cliente.consultarReserva(RESERVA, PEDIDO).orElseThrow().vencida()).isTrue();
    }

    @Test
    void enModoSimuladoNoConsultaNada() {
        ProductosClient simulado = new ProductosClient(true, BASE, RestClient.builder(), TokenServicio.sinCredenciales());
        assertThat(simulado.consultarOperacion("x")).isEmpty();
        assertThat(simulado.consultarReserva(RESERVA, PEDIDO)).isEmpty();
    }

    @Test
    void reservaSinRespuestaOCon5xxSeMarcaComoPosiblementeAdmitida() {
        servidor.expect(requestTo(BASE + "/api/v1/inventario/reservas")).andRespond(withServerError());
        assertThatThrownBy(() -> cliente.reservar(PEDIDO, "MARKETPLACE", List.of(new LineaReserva("SKU-AUR-01", 1))))
                .isInstanceOf(ReservaSinRespuestaException.class);
    }

    @Test
    void reservaRechazadaCon4xxNoSeMarcaComoPosiblementeAdmitida() {
        servidor.expect(requestTo(BASE + "/api/v1/inventario/reservas")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> cliente.reservar(PEDIDO, "MARKETPLACE", List.of(new LineaReserva("SKU-AUR-01", 1))))
                .isInstanceOf(DependenciaNoDisponibleException.class)
                .isNotInstanceOf(ReservaSinRespuestaException.class);
    }

    @Test
    void un429EnLaConsultaTraeElRetryAfter() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, "12");
        servidor.expect(requestTo(BASE + "/api/v1/inventario/operaciones/z"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(h));
        assertThatThrownBy(() -> cliente.consultarOperacion("z"))
                .isInstanceOf(LimiteProductosException.class)
                .extracting("reintentarEn").isEqualTo(java.time.Duration.ofSeconds(12));
    }
}

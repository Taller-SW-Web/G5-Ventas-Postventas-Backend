package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ventas.integracion.ProductosClient.LineaReserva;
import ventas.integracion.ProductosClient.MotivoLiberacion;
import ventas.integracion.ProductosClient.Operacion;
import ventas.integracion.ProductosClient.ResultadoConfirmacion;

/** Rutas, cabeceras y cuerpos según openapi.yaml de Productos (G6), contra un servidor HTTP falso. */
class ProductosClientTest {

    static final String PEDIDO = "PED-2026-00981";
    static final String BASE = "http://productos";

    MockRestServiceServer servidor;
    ProductosClient cliente;

    @BeforeEach
    void armar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new ProductosClient(false, BASE, builder);
    }

    @Test
    void elOperationIdEsSiempreElMismoParaLaMismaOperacion() {
        String reservar = ProductosClient.operacionId(Operacion.RESERVAR, PEDIDO);
        assertThat(reservar).isEqualTo(ProductosClient.operacionId(Operacion.RESERVAR, PEDIDO));
        assertThat(reservar).isNotEqualTo(ProductosClient.operacionId(Operacion.CONFIRMAR, PEDIDO));
        assertThat(java.util.UUID.fromString(reservar)).isNotNull();
    }

    @Test
    void reservarMandaElCuerpoDelContrato() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, PEDIDO);
        servidor.expect(requestTo(BASE + "/api/v1/inventario/reservas"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", op))
                .andExpect(content().json("""
                        {"order_id":"%s","operation_id":"%s","channel_id":"MARKETPLACE",
                         "lines":[{"sku":"SKU-AUR-01","quantity":2}]}""".formatted(PEDIDO, op), true))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"ACCEPTED\"}"));

        // Modo real: el resultado llega por RabbitMQ
        assertThat(cliente.reservar(PEDIDO, "MARKETPLACE", List.of(new LineaReserva("SKU-AUR-01", 2)))).isEmpty();
        servidor.verify();
    }

    @Test
    void siProductosNoAdmiteLaReservaResponde409ControladoRnf05() {
        servidor.expect(requestTo(BASE + "/api/v1/inventario/reservas")).andRespond(withServerError());

        assertThatThrownBy(() -> cliente.reservar(PEDIDO, "CHATBOT", List.of(new LineaReserva("SKU-1", 1))))
                .extracting("codigo").isEqualTo("PRODUCTOS_NO_DISPONIBLE");
    }

    @Test
    void confirmarMandaOrderIdYOperationId() {
        String op = ProductosClient.operacionId(Operacion.CONFIRMAR, PEDIDO);
        servidor.expect(requestTo(BASE + "/api/v1/inventario/reservas/RES-778/confirmar"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", op))
                .andExpect(content().json("{\"order_id\":\"%s\",\"operation_id\":\"%s\"}".formatted(PEDIDO, op), true))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(cliente.confirmar(PEDIDO, "RES-778")).isEqualTo(ResultadoConfirmacion.ACEPTADA);
        servidor.verify();
    }

    @Test
    void confirmarConReservaExpiradaLoAvisa() {
        servidor.expect(requestTo(BASE + "/api/v1/inventario/reservas/RES-778/confirmar"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":409,\"code\":\"RESERVA_EXPIRADA\"}"));

        assertThat(cliente.confirmar(PEDIDO, "RES-778")).isEqualTo(ResultadoConfirmacion.RESERVA_EXPIRADA);
    }

    @Test
    void liberarMandaElMotivo() {
        String op = ProductosClient.operacionId(Operacion.LIBERAR, PEDIDO);
        servidor.expect(requestTo(BASE + "/api/v1/inventario/reservas/RES-778/liberar"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", op))
                .andExpect(content().json("""
                        {"order_id":"%s","operation_id":"%s","reason":"PAGO_NO_COMPLETADO"}""".formatted(PEDIDO, op), true))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        cliente.liberar(PEDIDO, "RES-778", MotivoLiberacion.PAGO_NO_COMPLETADO);
        servidor.verify();
    }

    @Test
    void liberarNuncaLanzaExcepcion() {
        servidor.expect(requestTo(BASE + "/api/v1/inventario/reservas/RES-778/liberar")).andRespond(withServerError());

        cliente.liberar(PEDIDO, "RES-778", MotivoLiberacion.ANULACION);
        servidor.verify();
    }

    @Test
    void enModoSimuladoLaReservaQuedaCreadaAlInstante() {
        ProductosClient simulado = new ProductosClient(true, BASE, RestClient.builder());

        MensajeInventario m = simulado.reservar(PEDIDO, "CHATBOT", List.of(new LineaReserva("SKU-1", 1))).orElseThrow();

        assertThat(m.name()).isEqualTo(MensajeInventario.RESERVA_CREADA);
        assertThat(m.pedidoId()).isEqualTo(PEDIDO);
        assertThat(m.reservaId()).isEqualTo("SIM-" + PEDIDO);
        assertThat(m.operationId()).isEqualTo(ProductosClient.operacionId(Operacion.RESERVAR, PEDIDO));
    }
}

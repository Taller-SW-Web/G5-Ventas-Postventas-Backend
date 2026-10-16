package postventa.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import postventa.compartido.CanalVenta;

import postventa.compartido.CanalVenta;

class VentasClientTest {

    static final UUID CLIENTE = UUID.fromString("3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a");
    static final Instant AHORA = Instant.parse("2026-10-12T15:00:00Z");

    @Test
    void elProductoIdDeM1PasaTalCualSinConvertirloANumero() {
        VentasClient.PedidoM1 m1 = new VentasClient.PedidoM1("PED-2026-00981", "ENTREGADO",
                new VentasClient.ContactoM1(CLIENTE), new VentasClient.PagoM1(new BigDecimal("129.90"), "PEN"),
                List.of(new VentasClient.ItemM1("prd-013-01", 1, new BigDecimal("129.90"), "SKU-013-01")),
                OffsetDateTime.parse("2026-10-11T09:00:00Z"), List.of(), null);

        assertThat(VentasClient.convertir(m1).items())
                .containsExactly(new PedidoResumen.Item("prd-013-01", 1, new BigDecimal("129.90"), "SKU-013-01"));
    }

    @Test
    void laFechaDeEntregaEsLaDelHistorialEntregado() {
        VentasClient.PedidoM1 m1 = new VentasClient.PedidoM1("PED-2026-00981", "ENTREGADO",
                new VentasClient.ContactoM1(CLIENTE), new VentasClient.PagoM1(new BigDecimal("129.90"), "PEN"),
                List.of(new VentasClient.ItemM1("PROD-101", 2, new BigDecimal("64.95"), "SKU-101")),
                OffsetDateTime.parse("2026-10-11T09:00:00Z"),
                List.of(new VentasClient.HistorialM1("`CREADO`", OffsetDateTime.parse("2026-10-01T10:00:00Z")),
                        new VentasClient.HistorialM1("ENTREGADO", OffsetDateTime.parse("2026-10-05T18:30:00Z"))), "RETAIL");

        PedidoResumen p = VentasClient.convertir(m1);

        assertThat(p.entregado()).isTrue();
        assertThat(p.fechaEntrega()).isEqualTo(OffsetDateTime.parse("2026-10-05T18:30:00Z"));
        assertThat(p.clienteId()).isEqualTo(CLIENTE);
        assertThat(p.canal()).isEqualTo(CanalVenta.RETAIL);
        assertThat(p.items()).containsExactly(new PedidoResumen.Item("PROD-101", 2, new BigDecimal("64.95"), "SKU-101"));
    }

    @Test
    void unPedidoNoEntregadoNoTieneFechaDeEntrega() {
        VentasClient.PedidoM1 m1 = new VentasClient.PedidoM1("PED-2026-00981", "`EN_PREPARACION`", null, null,
                null, null, null, "CANAL_DESCONOCIDO");

        PedidoResumen p = VentasClient.convertir(m1);

        assertThat(p.estado()).isEqualTo("EN_PREPARACION");
        assertThat(p.canal()).isNull();
        assertThat(p.fechaEntrega()).isNull();
        assertThat(p.items()).isEmpty();
    }

    @Test
    void elModoSimuladoTieneCodigosParaProbarErrores() {
        VentasClient cliente = new VentasClient(true, CLIENTE, null, Clock.fixed(AHORA, ZoneOffset.UTC), RestClient.builder());

        assertThat(cliente.consultarPedido("PED-2026-00404")).isEmpty();
        assertThat(cliente.consultarPedido("PED-2026-00409")).get().extracting(PedidoResumen::estado).isEqualTo("DESPACHADO");
        assertThat(cliente.consultarPedido("PED-2026-00030")).get().extracting(PedidoResumen::fechaEntrega)
                .isEqualTo(OffsetDateTime.parse("2026-09-12T15:00:00Z"));
        assertThat(cliente.consultarPedido("PED-2026-00001")).get().satisfies(p -> {
            assertThat(p.entregado()).isTrue();
            assertThat(p.clienteId()).isEqualTo(CLIENTE);
            assertThat(p.canal()).isEqualTo(CanalVenta.MARKETPLACE);
        });
    }

    @Test
    void enModoRealLlamaALa13ConElTokenDeServicioDeM2() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://m1");
        MockRestServiceServer m1 = MockRestServiceServer.bindTo(builder).build();
        TokenServicio token = org.mockito.Mockito.mock(TokenServicio.class);
        org.mockito.Mockito.when(token.authorization()).thenReturn(java.util.Optional.of("Bearer token-m2"));
        m1.expect(requestTo("http://m1/api/v1/pedidos/PED-2026-00008"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-m2"))
                .andRespond(withSuccess("""
                        {"pedidoId":"PED-2026-00008","estado":"ENTREGADO",
                         "contacto":{"clienteId":"3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a"},
                         "pago":{"total":100.00,"moneda":"PEN"},
                         "canal":"MARKETPLACE",
                         "items":[{"productoId":"PROD-101","cantidad":2,"precioUnitario":50.00}],
                         "historialEstados":[{"estado":"ENTREGADO","fecha":"2026-10-13T05:28:45Z"}]}
                        """, MediaType.APPLICATION_JSON));
        VentasClient cliente = new VentasClient(false, CLIENTE, token, Clock.fixed(AHORA, ZoneOffset.UTC), builder);

        assertThat(cliente.consultarPedido("PED-2026-00008")).get().satisfies(p -> {
            assertThat(p.entregado()).isTrue();
            assertThat(p.items()).containsExactly(new PedidoResumen.Item("PROD-101", 2, new BigDecimal("50.00")));
            assertThat(p.canal()).isEqualTo(CanalVenta.MARKETPLACE);
        });
        m1.verify();
    }

    // ---------- 1.11 orden de reemplazo (CAMBIO)

    @Test
    void elReemplazoSimuladoLlegaEntregadoConElNumeroDeLaDevolucion() {
        VentasClient cliente = new VentasClient(true, CLIENTE, null, Clock.fixed(AHORA, ZoneOffset.UTC), RestClient.builder());

        VentasClient.Reemplazo r = cliente.solicitarReemplazo("DEV-2026-0042", "PED-2026-00981",
                List.of(new VentasClient.LineaReemplazo("SKU-101", 1)));

        assertThat(r.pedidoId()).isEqualTo("PED-2026-90042");
        assertThat(r.entregado()).isTrue();
    }

    @Test
    void enModoRealPideElReemplazoA111ConElTokenDeM2() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://m1");
        MockRestServiceServer m1 = MockRestServiceServer.bindTo(builder).build();
        TokenServicio token = org.mockito.Mockito.mock(TokenServicio.class);
        org.mockito.Mockito.when(token.authorization()).thenReturn(java.util.Optional.of("Bearer token-m2"));
        m1.expect(requestTo("http://m1/api/v1/pedidos/reemplazos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-m2"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json("""
                        {"devolucionId":"DEV-2026-0042","pedidoOrigenId":"PED-2026-00981",
                         "items":[{"sku":"SKU-101","cantidad":1}]}
                        """))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"pedidoId\":\"PED-2026-01002\",\"estado\":\"EN_PREPARACION\",\"devolucionId\":\"DEV-2026-0042\"}"));
        VentasClient cliente = new VentasClient(false, CLIENTE, token, Clock.fixed(AHORA, ZoneOffset.UTC), builder);

        VentasClient.Reemplazo r = cliente.solicitarReemplazo("DEV-2026-0042", "PED-2026-00981",
                List.of(new VentasClient.LineaReemplazo("SKU-101", 1)));

        assertThat(r).isEqualTo(new VentasClient.Reemplazo("PED-2026-01002", "EN_PREPARACION"));
        assertThat(r.entregado()).isFalse();
        m1.verify();
    }

    @Test
    void siM1RechazaElReemplazoResponde409() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://m1");
        MockRestServiceServer m1 = MockRestServiceServer.bindTo(builder).build();
        m1.expect(requestTo("http://m1/api/v1/pedidos/reemplazos"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"codigo\":\"PEDIDO_NO_ENTREGADO\"}"));
        TokenServicio token = org.mockito.Mockito.mock(TokenServicio.class);
        VentasClient cliente = new VentasClient(false, CLIENTE, token, Clock.fixed(AHORA, ZoneOffset.UTC), builder);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cliente.solicitarReemplazo("DEV-2026-0042",
                        "PED-2026-00981", List.of(new VentasClient.LineaReemplazo("SKU-101", 1))))
                .isInstanceOf(postventa.compartido.ConflictoException.class)
                .extracting("codigo").isEqualTo("REEMPLAZO_RECHAZADO");
    }
}

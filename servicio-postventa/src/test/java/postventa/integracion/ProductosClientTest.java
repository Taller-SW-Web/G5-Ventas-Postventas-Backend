package postventa.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import postventa.compartido.DependenciaNoDisponibleException;

/** Reintegro a Productos con el cuerpo acordado con G6 (issue #51), contra un Productos simulado. */
class ProductosClientTest {

    static final UUID OPERACION = UUID.fromString("0b6c2f4e-1a2b-4c3d-9e8f-123456789abc");
    static final List<ProductosClient.LineaReintegro> LINEAS = List.of(new ProductosClient.LineaReintegro("SKU-101", 1));

    @Test
    void enModoRealMandaElCuerpoAcordadoConG6YElTokenDeM2() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://productos");
        MockRestServiceServer g6 = MockRestServiceServer.bindTo(builder).build();
        g6.expect(requestTo("http://productos/api/v1/inventario/reintegros"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-m2"))
                .andExpect(content().json("""
                        {"operation_id":"0b6c2f4e-1a2b-4c3d-9e8f-123456789abc","order_id":"PED-2026-00981",
                         "source_ref":"DEV-2026-0042","reason":"DEVOLUCION_ACEPTADA",
                         "lines":[{"sku":"SKU-101","quantity":1,"location_id":"ALM-DEV"}]}
                        """, true))
                .andRespond(withSuccess("{\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));
        ProductosClient cliente = new ProductosClient(false, "ALM-DEV", token(), builder);

        cliente.reintegrar(OPERACION, "PED-2026-00981", "DEV-2026-0042", LINEAS);

        g6.verify();
    }

    @Test
    void sinLocationIdNoInventaUnoYResponde503() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://productos");
        MockRestServiceServer g6 = MockRestServiceServer.bindTo(builder).build();
        ProductosClient cliente = new ProductosClient(false, "", token(), builder);

        assertThatThrownBy(() -> cliente.reintegrar(OPERACION, "PED-2026-00981", "DEV-2026-0042", LINEAS))
                .isInstanceOf(DependenciaNoDisponibleException.class)
                .extracting("codigo").isEqualTo("PRODUCTOS_NO_DISPONIBLE");
        g6.verify();
    }

    @Test
    void siProductosFallaResponde503ParaReintentar() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://productos");
        MockRestServiceServer g6 = MockRestServiceServer.bindTo(builder).build();
        g6.expect(requestTo("http://productos/api/v1/inventario/reintegros")).andRespond(withServerError());
        ProductosClient cliente = new ProductosClient(false, "ALM-DEV", token(), builder);

        assertThatThrownBy(() -> cliente.reintegrar(OPERACION, "PED-2026-00981", "DEV-2026-0042", LINEAS))
                .isInstanceOf(DependenciaNoDisponibleException.class)
                .extracting("codigo").isEqualTo("PRODUCTOS_NO_DISPONIBLE");
    }

    @Test
    void enModoSimuladoNoLlamaANadieYPROD999NoTieneStock() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://productos");
        MockRestServiceServer g6 = MockRestServiceServer.bindTo(builder).build();
        ProductosClient cliente = new ProductosClient(true, "", token(), builder);

        cliente.reintegrar(OPERACION, "PED-2026-00981", "DEV-2026-0042", LINEAS);

        assertThat(cliente.hayDisponibilidad(List.of(new ProductosClient.ItemProducto("PROD-101", "SKU-101", 1)))).isTrue();
        assertThat(cliente.hayDisponibilidad(List.of(new ProductosClient.ItemProducto("PROD-999", "SKU-999", 1)))).isFalse();
        g6.verify();
    }

    @Test
    void enModoRealConsultaLaDisponibilidadPorSkuConElTokenDeM2() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://productos");
        MockRestServiceServer g6 = MockRestServiceServer.bindTo(builder).build();
        g6.expect(requestTo("http://productos/api/v1/inventario/disponibilidad?skus=SKU-101,SKU-102"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-m2"))
                .andRespond(withSuccess("""
                        {"items":[{"sku":"SKU-101","location_id":"L1","on_hand":5,"reserved":1,"blocked":0,
                                   "available":4,"status":"DISPONIBLE","threshold":2},
                                  {"sku":"SKU-102","available":1,"status":"STOCK_BAJO"}]}
                        """, MediaType.APPLICATION_JSON));
        ProductosClient cliente = new ProductosClient(false, "", token(), builder);

        assertThat(cliente.hayDisponibilidad(List.of(new ProductosClient.ItemProducto("PROD-101", "SKU-101", 2),
                new ProductosClient.ItemProducto("PROD-102", "SKU-102", 1)))).isTrue();
        g6.verify();
    }

    @Test
    void enModoRealSinUnidadesSuficientesOSkuDesconocidoNoHayStock() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://productos");
        MockRestServiceServer g6 = MockRestServiceServer.bindTo(builder).build();
        g6.expect(requestTo("http://productos/api/v1/inventario/disponibilidad?skus=SKU-101"))
                .andRespond(withSuccess("""
                        {"items":[{"sku":"SKU-101","available":1,"status":"STOCK_BAJO"}]}
                        """, MediaType.APPLICATION_JSON));
        g6.expect(requestTo("http://productos/api/v1/inventario/disponibilidad?skus=SKU-404"))
                .andRespond(withSuccess("""
                        {"items":[]}
                        """, MediaType.APPLICATION_JSON));
        ProductosClient cliente = new ProductosClient(false, "", token(), builder);

        assertThat(cliente.hayDisponibilidad(List.of(new ProductosClient.ItemProducto("PROD-101", "SKU-101", 2)))).isFalse();
        assertThat(cliente.hayDisponibilidad(List.of(new ProductosClient.ItemProducto("PROD-404", "SKU-404", 1)))).isFalse();
        g6.verify();
    }

    @Test
    void siLaDisponibilidadNoRespondeEs503() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://productos");
        MockRestServiceServer g6 = MockRestServiceServer.bindTo(builder).build();
        g6.expect(requestTo("http://productos/api/v1/inventario/disponibilidad?skus=SKU-101")).andRespond(withServerError());
        ProductosClient cliente = new ProductosClient(false, "", token(), builder);

        assertThatThrownBy(() -> cliente.hayDisponibilidad(List.of(new ProductosClient.ItemProducto("PROD-101", "SKU-101", 1))))
                .isInstanceOf(DependenciaNoDisponibleException.class)
                .extracting("codigo").isEqualTo("PRODUCTOS_NO_DISPONIBLE");
    }

    private static TokenServicio token() {
        TokenServicio token = mock(TokenServicio.class);
        when(token.authorization()).thenReturn(Optional.of("Bearer token-m2"));
        return token;
    }
}

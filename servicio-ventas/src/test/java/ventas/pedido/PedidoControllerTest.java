package ventas.pedido;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import ventas.compartido.SecurityConfig;
import ventas.pedido.dto.CrearPedidoResponse;

/** Sin base de datos: comprueba rutas, códigos HTTP y el formato de error del contrato. */
@WebMvcTest(PedidoController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "seguridad.habilitada=false")
class PedidoControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PedidoService pedidoService;

    @MockitoBean
    CrearPedidoService crearPedidoService;

    @MockitoBean
    NotificacionPagoService notificacionPagoService;

    @Test
    void losEndpointsPendientesDeF1Responden501() throws Exception {
        esperar501(mvc.perform(get("/api/v1/pedidos/PED-2026-00001")));
        esperar501(mvc.perform(get("/api/v1/pedidos")));
        esperar501(mvc.perform(patch("/api/v1/pedidos/PED-2026-00001/estado")));
        esperar501(mvc.perform(post("/api/v1/pedidos/PED-2026-00001/eventos-logistica")));
    }

    @Test
    void crearPedidoNuevoResponde201ConLocation() throws Exception {
        when(crearPedidoService.crear(eq(CLAVE), any())).thenReturn(new CrearPedidoService.Resultado(respuesta(), true));

        mvc.perform(post("/api/v1/pedidos").header("Idempotency-Key", CLAVE)
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/pedidos/PED-2026-00981"))
                .andExpect(jsonPath("$.pedidoId").value("PED-2026-00981"))
                .andExpect(jsonPath("$.estado").value("CREADO"))
                .andExpect(jsonPath("$.total").value(129.90))
                .andExpect(jsonPath("$.canal").value("CHATBOT"));
    }

    @Test
    void repeticionIdempotenteResponde200() throws Exception {
        when(crearPedidoService.crear(eq(CLAVE), any())).thenReturn(new CrearPedidoService.Resultado(respuesta(), false));

        mvc.perform(post("/api/v1/pedidos").header("Idempotency-Key", CLAVE)
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidoId").value("PED-2026-00981"));
    }

    @Test
    void cuerpoSinCamposObligatoriosResponde400ConDetalles() throws Exception {
        mvc.perform(post("/api/v1/pedidos").header("Idempotency-Key", CLAVE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"canal\":\"CHATBOT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalles").isNotEmpty());
        verifyNoInteractions(crearPedidoService);
    }

    @Test
    void canalDesconocidoResponde400() throws Exception {
        mvc.perform(post("/api/v1/pedidos").header("Idempotency-Key", CLAVE)
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO.replace("CHATBOT", "TIKTOK")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));
    }

    @Test
    void rutaInexistenteResponde404ConFormatoDelContrato() throws Exception {
        mvc.perform(get("/api/v1/no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RUTA_NO_ENCONTRADA"))
                .andExpect(jsonPath("$.detalles").isArray());
    }

    @Test
    void metodoNoPermitidoResponde405() throws Exception {
        mvc.perform(delete("/api/v1/pedidos/PED-2026-00001"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
    }

    static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    /** Ejemplo de api-ventas.md 1.1. */
    static final String CUERPO = """
            {
              "canal": "CHATBOT",
              "contacto": {
                "clienteId": "3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a",
                "nombreCompleto": "Juan Pérez Rodríguez",
                "tipoDocumento": "DNI",
                "numeroDocumento": "72458912",
                "telefono": "+51999888777",
                "email": "juan.perez@example.com"
              },
              "items": [
                {"productoId": "PROD-101", "sku": "SKU-AUR-01", "descripcion": "Audífonos", "cantidad": 1, "precioUnitario": 129.90}
              ],
              "cupon": {"codigo": "DESC10", "descuento": 10.00, "aplicado": true},
              "envio": {
                "modalidad": "DELIVERY", "costo": 10.00, "destinatario": "Juan Pérez",
                "departamento": "Lima", "provincia": "Lima", "distrito": "San Miguel",
                "direccion": "Av. La Marina 1234", "referencia": "Frente al centro comercial"
              },
              "pago": {
                "metodoPago": "TARJETA_CREDITO", "moneda": "PEN",
                "subtotal": 129.90, "descuentoCupon": 10.00, "costoEnvio": 10.00, "total": 129.90
              }
            }
            """;

    private static CrearPedidoResponse respuesta() {
        return new CrearPedidoResponse("PED-2026-00981", EstadoPedido.CREADO, new BigDecimal("129.90"), "PEN",
                CanalVenta.CHATBOT, OffsetDateTime.parse("2026-10-12T15:00:00Z"));
    }

    private static void esperar501(ResultActions resultado) throws Exception {
        resultado.andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.codigo").value("NO_IMPLEMENTADO"))
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.detalles").isArray());
    }
}

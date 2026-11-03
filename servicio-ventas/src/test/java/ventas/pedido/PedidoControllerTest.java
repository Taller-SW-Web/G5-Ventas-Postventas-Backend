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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ventas.compartido.ConflictoException;
import ventas.compartido.DependenciaNoDisponibleException;
import ventas.compartido.NoProcesableException;
import ventas.compartido.SecurityConfig;
import ventas.pedido.dto.CrearPedidoResponse;
import ventas.pedido.dto.DespachoPedidoResponse;
import ventas.pedido.dto.KpisVentasResponse;
import ventas.pedido.dto.ListadoPedidosResponse;
import ventas.pedido.dto.NotificacionPagoResponse;
import ventas.pedido.dto.PedidoDetalle;

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

    @MockitoBean
    ConsultarPedidoService consultarPedidoService;

    @MockitoBean
    DespachoPedidoService despachoPedidoService;

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
    void notificacionDePagoResponde200() throws Exception {
        when(notificacionPagoService.procesar(eq("PED-2026-00981"), any())).thenReturn(new NotificacionPagoResponse(
                "PED-2026-00981", EstadoPedido.PAGADO, "TX-PASARELA-778120", OffsetDateTime.parse("2026-09-23T00:06:00Z")));

        mvc.perform(post("/api/v1/pedidos/PED-2026-00981/pagos/notificacion")
                        .contentType(MediaType.APPLICATION_JSON).content(NOTIFICACION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidoId").value("PED-2026-00981"))
                .andExpect(jsonPath("$.nuevoEstado").value("PAGADO"))
                .andExpect(jsonPath("$.transaccionId").value("TX-PASARELA-778120"))
                .andExpect(jsonPath("$.fechaTransicion").value("2026-09-23T00:06:00Z"));
    }

    @Test
    void notificacionSinTransaccionResponde400() throws Exception {
        mvc.perform(post("/api/v1/pedidos/PED-2026-00981/pagos/notificacion")
                        .contentType(MediaType.APPLICATION_JSON).content(NOTIFICACION.replace("TX-PASARELA-778120", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalles[0].campo").value("transaccionId"));
        verifyNoInteractions(notificacionPagoService);
    }

    @Test
    void resultadoDesconocidoResponde400() throws Exception {
        mvc.perform(post("/api/v1/pedidos/PED-2026-00981/pagos/notificacion")
                        .contentType(MediaType.APPLICATION_JSON).content(NOTIFICACION.replace("APROBADO", "QUIZAS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));
    }

    @Test
    void consultarPedidoResponde200ConElContrato() throws Exception {
        when(consultarPedidoService.consultar(eq("PED-2026-00981"), any())).thenReturn(new PedidoDetalle(
                "PED-2026-00981", CanalVenta.CHATBOT, EstadoPedido.ENTREGADO,
                new PedidoDetalle.Contacto(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "Juan", "DNI", "72458912", "j@x.com", null),
                new PedidoDetalle.Pago(new BigDecimal("129.90"), "PEN", "TARJETA_CREDITO"),
                new PedidoDetalle.Envio("DELIVERY", "Av. La Marina 1234", "San Miguel", null, "Lima", "Lima", null, null),
                java.util.List.of(new PedidoDetalle.Item("PROD-101", "SKU-AUR-01", "Audífonos", 1, new BigDecimal("129.90"))),
                OffsetDateTime.parse("2026-09-23T00:05:00Z"), OffsetDateTime.parse("2026-09-25T10:00:00Z"),
                java.util.List.of(new PedidoDetalle.Historial(EstadoPedido.CREADO,
                        OffsetDateTime.parse("2026-09-23T00:05:00Z"), ActorHistorial.CANAL_CHATBOT, "Registro inicial de compra"))));

        mvc.perform(get("/api/v1/pedidos/PED-2026-00981"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidoId").value("PED-2026-00981"))
                .andExpect(jsonPath("$.estado").value("ENTREGADO"))
                .andExpect(jsonPath("$.contacto.clienteId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.pago.metodoPago").value("TARJETA_CREDITO"))
                .andExpect(jsonPath("$.items[0].productoId").value("PROD-101"))
                .andExpect(jsonPath("$.historialEstados[0].actor").value("CANAL_CHATBOT"))
                .andExpect(jsonPath("$.ultimaActualizacion").value("2026-09-25T10:00:00Z"));
    }

    @Test
    void productosNoDisponibleResponde503() throws Exception {
        when(crearPedidoService.crear(eq(CLAVE), any())).thenThrow(new ventas.compartido.DependenciaNoDisponibleException(
                "PRODUCTOS_NO_DISPONIBLE", "No se pudo reservar el stock en Productos; intente nuevamente"));

        mvc.perform(post("/api/v1/pedidos").header("Idempotency-Key", CLAVE)
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("PRODUCTOS_NO_DISPONIBLE"));
    }

    @Test
    void consultarPedidoInexistenteResponde404() throws Exception {
        when(consultarPedidoService.consultar(eq("PED-2026-99999"), any()))
                .thenThrow(new ventas.compartido.RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO", "No existe"));

        mvc.perform(get("/api/v1/pedidos/PED-2026-99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("PEDIDO_NO_ENCONTRADO"));
    }

    // ---------- 1.4 Listar pedidos ----------

    @Test
    void listarPasaLosFiltrosAlServicio() throws Exception {
        UUID cliente = UUID.fromString("3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a");
        when(pedidoService.listar(cliente, EstadoPedido.PAGADO, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13), null, 1, 20))
                .thenReturn(new ListadoPedidosResponse(cliente, List.of(), 1, 0, 0));

        mvc.perform(get("/api/v1/pedidos").param("clienteId", cliente.toString()).param("estado", "PAGADO")
                        .param("desde", "2026-10-01").param("hasta", "2026-10-13").param("pagina", "1").param("tamano", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clienteId").value(cliente.toString()))
                .andExpect(jsonPath("$.contenido").isArray())
                .andExpect(jsonPath("$.pagina").value(1))
                .andExpect(jsonPath("$.totalPaginas").value(0))
                .andExpect(jsonPath("$.totalElementos").value(0));
    }

    @Test
    void listarUsaPaginaCeroYTamanoDiezPorDefecto() throws Exception {
        UUID cliente = UUID.fromString("3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a");
        when(pedidoService.listar(cliente, null, null, null, null, null, null, 0, 10))
                .thenReturn(new ListadoPedidosResponse(cliente, List.of(), 0, 0, 0));
        mvc.perform(get("/api/v1/pedidos").param("clienteId", cliente.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void listarSinClienteConLosFiltrosDeLaBandeja() throws Exception {
        when(pedidoService.listar(null, EstadoPedido.PAGADO, CanalVenta.MARKETPLACE, "Ana", null, null,
                PedidosFiltro.Orden.ANTIGUOS, 0, 10))
                .thenReturn(new ListadoPedidosResponse(null, List.of(), 0, 0, 0));

        mvc.perform(get("/api/v1/pedidos").param("estado", "PAGADO").param("canal", "MARKETPLACE")
                        .param("buscar", "Ana").param("orden", "ANTIGUOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(0));
    }

    @Test
    void listarConFechaOEstadoMalFormadoResponde400() throws Exception {
        mvc.perform(get("/api/v1/pedidos").param("clienteId", "3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a").param("desde", "01/10/2026"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pedidos").param("clienteId", "3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a").param("estado", "VOLANDO"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(pedidoService);
    }

    // ---------- 1.14 KPIs del Dashboard ----------

    @Test
    void kpisDevuelvenElFormatoDelContrato() throws Exception {
        LocalDate desde = LocalDate.of(2026, 10, 1);
        LocalDate hasta = LocalDate.of(2026, 10, 2);
        when(pedidoService.kpis(desde, hasta, CanalVenta.CHATBOT)).thenReturn(new KpisVentasResponse(
                desde, hasta, CanalVenta.CHATBOT, new BigDecimal("129.90"), 1, new BigDecimal("129.90"), 2, 1, 1,
                List.of(new KpisVentasResponse.VentaDia(desde, new BigDecimal("129.90")),
                        new KpisVentasResponse.VentaDia(hasta, BigDecimal.ZERO)),
                Map.of(CanalVenta.CHATBOT, new BigDecimal("129.90"))));

        mvc.perform(get("/api/v1/pedidos/kpis").param("desde", "2026-10-01").param("hasta", "2026-10-02")
                        .param("canal", "CHATBOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.montoVendido").value(129.90))
                .andExpect(jsonPath("$.pedidosVendidos").value(1))
                .andExpect(jsonPath("$.ticketPromedio").value(129.90))
                .andExpect(jsonPath("$.ventasPorDia[0].fecha").value("2026-10-01"))
                .andExpect(jsonPath("$.ventasPorCanal.CHATBOT").value(129.90));
        verifyNoInteractions(consultarPedidoService);   // "kpis" no se tomó como pedidoId
    }

    @Test
    void kpisConFechaMalFormadaResponde400() throws Exception {
        mvc.perform(get("/api/v1/pedidos/kpis").param("desde", "01/10/2026").param("hasta", "2026-10-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));
        verifyNoInteractions(pedidoService);
    }

    // ---------- Botones del Gestor: preparación y paquete sellado ----------

    @Test
    void iniciarPreparacionYPaqueteSelladoResponden200() throws Exception {
        when(despachoPedidoService.iniciarPreparacion("PED-2026-00981")).thenReturn(
                new DespachoPedidoResponse("PED-2026-00981", EstadoPedido.EN_PREPARACION, null, "Pedido en preparación"));
        when(despachoPedidoService.paqueteSellado("PED-2026-00981")).thenReturn(
                new DespachoPedidoResponse("PED-2026-00981", EstadoPedido.EN_PREPARACION, "DESP-00412", "Despacho solicitado a G2"));

        mvc.perform(post("/api/v1/pedidos/PED-2026-00981/preparacion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_PREPARACION"));
        mvc.perform(post("/api/v1/pedidos/PED-2026-00981/despacho"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDespacho").value("DESP-00412"));
    }

    @Test
    void sinCoberturaResponde422YDespachoCaidoResponde503() throws Exception {
        when(despachoPedidoService.paqueteSellado("PED-2026-00981"))
                .thenThrow(new NoProcesableException("DESP_ERROR_SIN_COBERTURA", "sin cobertura"))
                .thenThrow(new DependenciaNoDisponibleException("DESPACHO_NO_DISPONIBLE", "caído"));

        mvc.perform(post("/api/v1/pedidos/PED-2026-00981/despacho"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("DESP_ERROR_SIN_COBERTURA"));
        mvc.perform(post("/api/v1/pedidos/PED-2026-00981/despacho"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("DESPACHO_NO_DISPONIBLE"));
    }

    @Test
    void lasRutasViejasDe15Y16Responden404() throws Exception {
        mvc.perform(patch("/api/v1/pedidos/PED-2026-00981/estado").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/pedidos/PED-2026-00981/eventos-logistica").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
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

    /** Ejemplo de api-ventas.md 1.2. */
    static final String NOTIFICACION = """
            {
              "transaccionId": "TX-PASARELA-778120",
              "resultado": "APROBADO",
              "monto": 129.90,
              "moneda": "PEN",
              "metodo": "TARJETA_CREDITO",
              "marcaTarjeta": "VISA",
              "ultimosCuatroDigitos": "4321",
              "fechaPago": "2026-09-23T00:06:00Z",
              "codigoAutorizacion": "AUTH-99021"
            }
            """;

    private static CrearPedidoResponse respuesta() {
        return new CrearPedidoResponse("PED-2026-00981", EstadoPedido.CREADO, new BigDecimal("129.90"), "PEN",
                CanalVenta.CHATBOT, OffsetDateTime.parse("2026-10-12T15:00:00Z"));
    }

}

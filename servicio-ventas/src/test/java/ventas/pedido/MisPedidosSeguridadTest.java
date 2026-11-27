package ventas.pedido;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ventas.compartido.SecurityConfig;
import ventas.compartido.ValidadorToken;
import ventas.pedido.dto.MisPedidosResponse;

/**
 * Prueba de punta a punta de la seguridad de /pedidos/me con tokens RS256 firmados de verdad
 * (el mock Prism de Seguridad no firma tokens, kit §2 "Qué prueban los mocks").
 */
@WebMvcTest(controllers = {PedidoController.class, DespachoEventosController.class, ReemplazoController.class, EvidenciaCuponController.class}, properties = {
        "seguridad.habilitada=true",
        "seguridad.issuer=" + MisPedidosSeguridadTest.ISSUER,
        "seguridad.jwks-url=http://localhost:1/no-se-usa-en-pruebas"
})
@Import({SecurityConfig.class, ValidadorPedido.class, MisPedidosSeguridadTest.Claves.class})
class MisPedidosSeguridadTest {

    static final String ISSUER = "http://localhost:8080/api/v1/auth";
    static final UUID CLIENTE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final KeyPair CLAVES = generar();

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder firmador;
    @MockitoBean PedidoService pedidoService;
    @MockitoBean CrearPedidoService crearPedidoService;
    @MockitoBean NotificacionPagoService notificacionPagoService;
    @MockitoBean ConsultarPedidoService consultarPedidoService;
    @MockitoBean DespachoPedidoService despachoPedidoService;
    @MockitoBean EventoDespachoService eventoDespachoService;
    @MockitoBean ReemplazoService reemplazoService;
    @MockitoBean EvidenciaCuponService evidenciaCuponService;
    @MockitoBean PickupPedidoService pickupPedidoService;

    @TestConfiguration
    static class Claves {
        /** Mismo armado que SecurityConfig, pero con la clave pública de la prueba en vez del JWKS remoto. */
        @Bean @Primary
        JwtDecoder jwtDecoderDePrueba() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) CLAVES.getPublic())
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(Duration.ZERO),
                    new JwtIssuerValidator(ISSUER),
                    new ValidadorToken("api-ventas")));
            return decoder;
        }

        @Bean
        JwtEncoder firmador() {
            RSAKey rsa = new RSAKey.Builder((RSAPublicKey) CLAVES.getPublic())
                    .privateKey(CLAVES.getPrivate()).keyID("prueba").build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
        }
    }

    @Test
    void elDisenoDeLaApiEsPublicoPeroLosDatosNo() throws Exception {
        mvc.perform(get("/openapi/ventas.yaml")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/pedidos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void sinTokenResponde401ConElFormatoDelContrato() throws Exception {
        mvc.perform(get("/api/v1/pedidos/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"));
    }

    @Test
    void clienteVeSusPedidosFiltradosPorSub() throws Exception {
        when(pedidoService.listarMios(eq(CLIENTE), isNull(), isNull(), isNull(), eq(1), eq(10)))
                .thenReturn(new MisPedidosResponse(List.of(), 0, 1, 10));

        mvc.perform(get("/api/v1/pedidos/me").header("Authorization", "Bearer " + usuario(ISSUER, List.of("CLIENTE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(10));

        verify(pedidoService).listarMios(eq(CLIENTE), isNull(), isNull(), isNull(), eq(1), eq(10));
    }

    @Test
    void filtroPorFechasLlegaAlServicio() throws Exception {
        when(pedidoService.listarMios(eq(CLIENTE), eq(EstadoPedido.ENTREGADO), eq(java.time.LocalDate.parse("2026-10-01")),
                eq(java.time.LocalDate.parse("2026-10-31")), eq(2), eq(5)))
                .thenReturn(new MisPedidosResponse(List.of(), 0, 2, 5));

        mvc.perform(get("/api/v1/pedidos/me").param("estado", "ENTREGADO").param("desde", "2026-10-01")
                        .param("hasta", "2026-10-31").param("page", "2").param("pageSize", "5")
                        .header("Authorization", "Bearer " + usuario(ISSUER, List.of("CLIENTE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(5));
    }

    @Test
    void fechaConOtroFormatoResponde400() throws Exception {
        mvc.perform(get("/api/v1/pedidos/me").param("desde", "01/10/2026")
                        .header("Authorization", "Bearer " + usuario(ISSUER, List.of("CLIENTE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));
    }

    @Test
    void usuarioSinRolClienteRecibe403() throws Exception {
        mvc.perform(get("/api/v1/pedidos/me").header("Authorization", "Bearer " + usuario(ISSUER, List.of("ADMIN_VENTAS"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    void tokenDeOtroEmisorRecibe401() throws Exception {
        mvc.perform(get("/api/v1/pedidos/me")
                        .header("Authorization", "Bearer " + usuario("http://otro-emisor/auth", List.of("CLIENTE"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDeServicioNoPuedeUsarMe() throws Exception {
        mvc.perform(get("/api/v1/pedidos/me")
                        .header("Authorization", "Bearer " + servicio("ventas:pedidos:crear")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenDeServicioConScopeCrearPasaLaSeguridadDe1_1() throws Exception {
        // Sin cuerpo: 400 significa que la seguridad lo dejó pasar y falló la validación
        mvc.perform(post("/api/v1/pedidos").header("Authorization", "Bearer " + servicio("ventas:pedidos:crear")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tokenDeServicioSinScopeCrearRecibe403En1_1() throws Exception {
        mvc.perform(post("/api/v1/pedidos").header("Authorization", "Bearer " + servicio("ventas:pedidos:estado")))
                .andExpect(status().isForbidden());
    }

    @Test
    void marketplaceNoPuedeCrearUnPedidoDeRetail() throws Exception {
        // El cuerpo es válido; lo único que cambia es el canal. Debe rechazarse antes de crear nada
        String retail = PedidoControllerTest.CUERPO.replace("\"CHATBOT\"", "\"RETAIL\"");
        mvc.perform(post("/api/v1/pedidos").header("Authorization", "Bearer " + servicio("ventas:pedidos:crear"))
                        .contentType("application/json").content(retail))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    void marketplacePuedeCrearUnPedidoDeMarketplace() throws Exception {
        String marketplace = PedidoControllerTest.CUERPO.replace("\"CHATBOT\"", "\"MARKETPLACE\"");
        mvc.perform(post("/api/v1/pedidos").header("Authorization", "Bearer " + servicio("ventas:pedidos:crear"))
                        .contentType("application/json").content(marketplace))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(
                        403, result.getResponse().getStatus()));
    }

    @Test
    void servicioSinCanalAsignadoNoPuedeCrearPedidos() throws Exception {
        mvc.perform(post("/api/v1/pedidos").header("Authorization", "Bearer " + servicioDe("modulo-ventas", "ventas:pedidos:crear"))
                        .contentType("application/json").content(PedidoControllerTest.CUERPO))
                .andExpect(status().isForbidden());
    }

    @Test
    void notificacionDePagoExigeScopeWebhook() throws Exception {
        mvc.perform(post("/api/v1/pedidos/PED-2026-00001/pagos/notificacion")
                        .header("Authorization", "Bearer " + servicio("ventas:pedidos:crear")))
                .andExpect(status().isForbidden());
        // Con el scope de la pasarela pasa la seguridad (400 por cuerpo vacío)
        mvc.perform(post("/api/v1/pedidos/PED-2026-00001/pagos/notificacion")
                        .header("Authorization", "Bearer " + servicio("ventas:pagos:webhook")))
                .andExpect(status().isBadRequest());
    }

    // ---------- 1.3 Consultar pedido ----------

    @Test
    void m2ConScopeConsultarVeUnPedidoPuntual() throws Exception {
        mvc.perform(get("/api/v1/pedidos/PED-2026-00001")
                        .header("Authorization", "Bearer " + servicio("ventas:pedidos:consultar")))
                .andExpect(status().isOk());
        // Token de servicio de M2: sin restricción de titular
        verify(consultarPedidoService).consultar(eq("PED-2026-00001"), eq(Optional.empty()), eq(false));
    }

    @Test
    void retailConSuTokenConsultaSoloSusPedidos() throws Exception {
        mvc.perform(get("/api/v1/pedidos/PED-2026-00001")
                        .header("Authorization", "Bearer " + servicioDe("modulo-retail", "ventas:pedidos:consultar")))
                .andExpect(status().isOk());
        // Retail: sin restricción de titular, pero solo pedidos de tienda (lo resuelve ConsultarPedidoService)
        verify(consultarPedidoService).consultar(eq("PED-2026-00001"), eq(Optional.empty()), eq(true));
    }

    @Test
    void elScopeConsultarNoSirveParaListar() throws Exception {
        mvc.perform(get("/api/v1/pedidos").header("Authorization", "Bearer " + servicio("ventas:pedidos:consultar")))
                .andExpect(status().isForbidden());
    }

    @Test
    void retailConScopeBuscarDocumentoBuscaSoloEnSusPedidos() throws Exception {
        mvc.perform(get("/api/v1/pedidos").param("buscar", "45678912")
                        .header("Authorization", "Bearer " + servicioDe("modulo-retail", "ventas:pedidos:buscar-documento")))
                .andExpect(status().isOk());
        // Con token de servicio va a la búsqueda acotada (1.4c), nunca al listado completo del Gestor
        verify(pedidoService).buscarParaTienda(eq("45678912"), isNull(), isNull(), isNull(), isNull(), eq(0), eq(10));
        verify(pedidoService, never()).listar(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void laEvidenciaDeCuponSoloLaLeePromotionsConSuScope() throws Exception {
        String ruta = "/api/v1/integraciones/productos/pedidos/PED-2026-00001/evidencia-cupon";
        mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
        mvc.perform(get(ruta).header("Authorization", "Bearer " + servicioDe("promotions-svc", "ventas:pedidos:consultar")))
                .andExpect(status().isForbidden());
        // modulo-ventas con el scope correcto igual recibe 403: solo promotions-svc (contrato G6)
        mvc.perform(get(ruta).header("Authorization", "Bearer " + servicioDe("modulo-ventas", "ventas:pedidos:evidencia-cupon:leer")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ruta).header("Authorization", "Bearer " + servicioDe("promotions-svc", "ventas:pedidos:evidencia-cupon:leer")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
    }

    @Test
    void gestorPuedeListarYClienteNo() throws Exception {
        mvc.perform(get("/api/v1/pedidos").param("clienteId", CLIENTE.toString())
                        .header("Authorization", "Bearer " + usuario(ISSUER, List.of("ADMIN_VENTAS"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/pedidos").param("clienteId", CLIENTE.toString())
                        .header("Authorization", "Bearer " + usuario(ISSUER, List.of("CLIENTE"))))
                .andExpect(status().isForbidden());
    }

    // ---------- 1.14 KPIs del Dashboard ----------

    @Test
    void losKpisSonSoloDelGestorYNoSeConfundenConUnPedidoId() throws Exception {
        mvc.perform(get("/api/v1/pedidos/kpis").param("desde", "2026-10-01").param("hasta", "2026-10-26")
                        .header("Authorization", "Bearer " + usuario(ISSUER, List.of("ADMIN_VENTAS"))))
                .andExpect(status().isOk());
        // Sin la regla propia, /pedidos/* (1.3) dejaría pasar a un CLIENTE
        mvc.perform(get("/api/v1/pedidos/kpis").param("desde", "2026-10-01").param("hasta", "2026-10-26")
                        .header("Authorization", "Bearer " + usuario(ISSUER, List.of("CLIENTE"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pedidos/kpis").param("desde", "2026-10-01").param("hasta", "2026-10-26")
                        .header("Authorization", "Bearer " + servicio("ventas:pedidos:consultar")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(consultarPedidoService);
    }

    // ---------- 1.5 Webhook de Despacho ----------

    @Test
    void elWebhookDeDespachoSoloAceptaElScopeEstado() throws Exception {
        String cuerpo = "{\"idEvento\": \"evt-1\", \"idPedido\": \"PED-2026-00001\", \"tipo\": \"DESPACHO_ASIGNADO\"}";
        String clave = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
        mvc.perform(post("/api/v1/integraciones/despachos/eventos").header("Idempotency-Key", clave)
                        .header("Authorization", "Bearer " + servicio("ventas:pedidos:estado"))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/integraciones/despachos/eventos").header("Idempotency-Key", clave)
                        .header("Authorization", "Bearer " + servicio("ventas:pedidos:crear"))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/integraciones/despachos/eventos").header("Idempotency-Key", clave)
                        .header("Authorization", "Bearer " + usuario(ISSUER, List.of("ADMIN_VENTAS")))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden());
    }

    @Test
    void elReemplazoSoloLoPideM2ConSuScope() throws Exception {
        String cuerpo = "{\"devolucionId\": \"DEV-2026-0042\", \"pedidoOrigenId\": \"PED-2026-00981\","
                + " \"items\": [{\"sku\": \"SKU-AUR-01\", \"cantidad\": 1}]}";
        when(reemplazoService.crear(org.mockito.ArgumentMatchers.any())).thenReturn(new ReemplazoService.Resultado(
                new ventas.pedido.dto.CrearReemplazoResponse("PED-2026-01002", EstadoPedido.EN_PREPARACION,
                        "DEV-2026-0042", "PED-2026-00981", null), true));
        mvc.perform(post("/api/v1/pedidos/reemplazos").header("Authorization", "Bearer " + servicio("ventas:pedidos:reemplazar"))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pedidoId").value("PED-2026-01002"));
        mvc.perform(post("/api/v1/pedidos/reemplazos").header("Authorization", "Bearer " + servicio("ventas:pedidos:crear"))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pedidos/reemplazos").header("Authorization", "Bearer " + usuario(ISSUER, List.of("ADMIN_VENTAS")))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden());
    }

    @Test
    void elReemplazoValidaElCuerpo() throws Exception {
        mvc.perform(post("/api/v1/pedidos/reemplazos").header("Authorization", "Bearer " + servicio("ventas:pedidos:reemplazar"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"devolucionId\": \"X-1\", \"pedidoOrigenId\": \"PED-2026-00981\", \"items\": []}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reemplazoService);
    }

    @Test
    void lasRutasViejasDe15Y16YaNoExisten() throws Exception {
        mvc.perform(patch("/api/v1/pedidos/PED-2026-00001/estado").header("Authorization", "Bearer " + servicio("ventas:pedidos:estado")))
                .andExpect(status().isForbidden());   // denyAll: ninguna regla la permite
        mvc.perform(post("/api/v1/pedidos/PED-2026-00001/eventos-logistica").header("Authorization", "Bearer " + servicio("ventas:pedidos:estado")))
                .andExpect(status().isForbidden());
    }

    @Test
    void losBotonesDePreparacionYDespachoSonSoloDelGestor() throws Exception {
        for (String ruta : List.of("/api/v1/pedidos/PED-2026-00001/preparacion", "/api/v1/pedidos/PED-2026-00001/despacho")) {
            mvc.perform(post(ruta).header("Authorization", "Bearer " + usuario(ISSUER, List.of("ADMIN_VENTAS"))))
                    .andExpect(status().isOk());
            mvc.perform(post(ruta).header("Authorization", "Bearer " + usuario(ISSUER, List.of("CLIENTE"))))
                    .andExpect(status().isForbidden());
            mvc.perform(post(ruta).header("Authorization", "Bearer " + servicio("ventas:pedidos:estado")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void tokenDeServicioSinScopeConsultarRecibe403() throws Exception {
        mvc.perform(get("/api/v1/pedidos/PED-2026-00001").header("Authorization", "Bearer " + servicio("ventas:pedidos:crear")))
                .andExpect(status().isForbidden());
    }

    @Test
    void clienteConsultaSoloComoTitular() throws Exception {
        mvc.perform(get("/api/v1/pedidos/PED-2026-00001").header("Authorization", "Bearer " + usuario(ISSUER, List.of("CLIENTE"))))
                .andExpect(status().isOk());
        verify(consultarPedidoService).consultar(eq("PED-2026-00001"), eq(Optional.of(CLIENTE)), eq(false));
    }

    @Test
    void gestorConsultaCualquierPedido() throws Exception {
        mvc.perform(get("/api/v1/pedidos/PED-2026-00001").header("Authorization", "Bearer " + usuario(ISSUER, List.of("ADMIN_VENTAS"))))
                .andExpect(status().isOk());
        verify(consultarPedidoService).consultar(eq("PED-2026-00001"), eq(Optional.empty()), eq(false));
    }

    @Test
    void clienteNoPuedeCrearPedidosDirecto() throws Exception {
        mvc.perform(post("/api/v1/pedidos").header("Authorization", "Bearer " + usuario(ISSUER, List.of("CLIENTE"))))
                .andExpect(status().isForbidden());
    }

    private String usuario(String issuer, List<String> roles) {
        Instant ahora = Instant.now();
        return firmar(JwtClaimsSet.builder()
                .issuer(issuer).subject(CLIENTE.toString())
                .issuedAt(ahora).expiresAt(ahora.plusSeconds(900))
                .id(UUID.randomUUID().toString())
                .claims(c -> c.putAll(Map.of("tipo", "acceso", "roles", roles, "permisos", List.of())))
                .build());
    }

    private String servicio(String scope) {
        return servicioDe("modulo-marketplace", scope);
    }

    private String servicioDe(String clientId, String scope) {
        Instant ahora = Instant.now();
        return firmar(JwtClaimsSet.builder()
                .issuer(ISSUER).subject(clientId).audience(List.of("api-ventas"))
                .issuedAt(ahora).expiresAt(ahora.plusSeconds(3600))
                .claim("tipo", "servicio").claim("scope", scope)
                .build());
    }

    private String firmar(JwtClaimsSet claims) {
        JwsHeader cabecera = JwsHeader.with(SignatureAlgorithm.RS256).keyId("prueba").build();
        return firmador.encode(JwtEncoderParameters.from(cabecera, claims)).getTokenValue();
    }

    private static KeyPair generar() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package ventas.pedido;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
@WebMvcTest(controllers = {PedidoController.class, DespachoEventosController.class, ReemplazoController.class}, properties = {
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
    void sinTokenResponde401ConElFormatoDelContrato() throws Exception {
        mvc.perform(get("/api/v1/pedidos/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"));
    }

    @Test
    void clienteVeSusPedidosFiltradosPorSub() throws Exception {
        when(pedidoService.listarMios(eq(CLIENTE), isNull(), eq(1), eq(10)))
                .thenReturn(new MisPedidosResponse(List.of(), 0, 1, 10));

        mvc.perform(get("/api/v1/pedidos/me").header("Authorization", "Bearer " + usuario(ISSUER, List.of("CLIENTE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(10));

        verify(pedidoService).listarMios(eq(CLIENTE), isNull(), eq(1), eq(10));
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
        verify(consultarPedidoService).consultar(eq("PED-2026-00001"), eq(Optional.empty()));
    }

    @Test
    void elScopeConsultarNoSirveParaListar() throws Exception {
        mvc.perform(get("/api/v1/pedidos").header("Authorization", "Bearer " + servicio("ventas:pedidos:consultar")))
                .andExpect(status().isForbidden());
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
        verify(consultarPedidoService).consultar(eq("PED-2026-00001"), eq(Optional.of(CLIENTE)));
    }

    @Test
    void gestorConsultaCualquierPedido() throws Exception {
        mvc.perform(get("/api/v1/pedidos/PED-2026-00001").header("Authorization", "Bearer " + usuario(ISSUER, List.of("ADMIN_VENTAS"))))
                .andExpect(status().isOk());
        verify(consultarPedidoService).consultar(eq("PED-2026-00001"), eq(Optional.empty()));
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
        Instant ahora = Instant.now();
        return firmar(JwtClaimsSet.builder()
                .issuer(ISSUER).subject("modulo-marketplace").audience(List.of("api-ventas"))
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

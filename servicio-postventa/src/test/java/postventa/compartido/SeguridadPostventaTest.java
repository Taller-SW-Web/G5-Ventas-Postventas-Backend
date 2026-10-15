package postventa.compartido;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import postventa.dashboard.DashboardService;
import postventa.devolucion.DevolucionService;
import postventa.devolucion.EvidenciaService;
import postventa.reembolso.ReembolsoService;
import postventa.reclamo.ReclamoService;

/**
 * Reglas de acceso de M2 (acuerdo A10) con tokens RS256 firmados de verdad.
 * Los services se simulan: solo importa si la seguridad deja pasar (cualquier respuesta distinta de 401 y 403).
 */
@WebMvcTest(properties = {
        "seguridad.habilitada=true",
        "seguridad.issuer=" + SeguridadPostventaTest.ISSUER,
        "seguridad.jwks-url=http://localhost:1/no-se-usa-en-pruebas"
})
@Import({SecurityConfig.class, SeguridadPostventaTest.Claves.class})
class SeguridadPostventaTest {

    static final String ISSUER = "http://localhost:8080/api/v1/auth";
    static final KeyPair CLAVES = generar();

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder firmador;

    @MockitoBean DevolucionService devolucionService;
    @MockitoBean EvidenciaService evidenciaService;
    @MockitoBean ReembolsoService reembolsoService;
    @MockitoBean ReclamoService reclamoService;
    @MockitoBean DashboardService dashboardService;

    @TestConfiguration
    static class Claves {
        @Bean @Primary
        JwtDecoder jwtDecoderDePrueba() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) CLAVES.getPublic())
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(Duration.ZERO),
                    new JwtIssuerValidator(ISSUER),
                    new ValidadorToken("api-postventa")));
            return decoder;
        }

        @Bean
        JwtEncoder firmador() {
            RSAKey rsa = new RSAKey.Builder((RSAPublicKey) CLAVES.getPublic())
                    .privateKey(CLAVES.getPrivate()).keyID("prueba").build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
        }
    }

    // ---------- token del cliente (antes eran scopes que Seguridad nunca emite) ----------

    @Test
    void clientePuedeUsarSusRutasMe() throws Exception {
        pasa(mvc.perform(get("/api/v2/devoluciones/me").header("Authorization", "Bearer " + usuario("CLIENTE"))));
        pasa(mvc.perform(get("/api/v2/reclamos/me").header("Authorization", "Bearer " + usuario("CLIENTE"))));
    }

    @Test
    void csatUsaElTokenDelCliente() throws Exception {
        pasa(mvc.perform(post("/api/v2/csat").header("Authorization", "Bearer " + usuario("CLIENTE"))));
        // El scope viejo postventa:csat:escribir ya no sirve
        mvc.perform(post("/api/v2/csat").header("Authorization", "Bearer " + servicio("postventa:csat:escribir")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    void scopesDeLecturaViejosYaNoDanAcceso() throws Exception {
        mvc.perform(get("/api/v2/devoluciones/me").header("Authorization", "Bearer " + servicio("postventa:devoluciones:leer")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v2/reclamos").header("Authorization", "Bearer " + servicio("postventa:reclamos:leer")))
                .andExpect(status().isForbidden());
    }

    // ---------- Gestor (rol, no scope ventas:admin) ----------

    @Test
    void gestorUsaElRolAdminVentas() throws Exception {
        pasa(mvc.perform(get("/api/v2/dashboard/metricas").header("Authorization", "Bearer " + usuario("ADMIN_VENTAS"))));
        pasa(mvc.perform(patch("/api/v2/devoluciones/DEV-2026-0001/resolucion").header("Authorization", "Bearer " + usuario("ADMIN_VENTAS"))));
        pasa(mvc.perform(get("/api/v2/reclamos").header("Authorization", "Bearer " + usuario("ADMIN_VENTAS"))));
        mvc.perform(get("/api/v2/dashboard/metricas").header("Authorization", "Bearer " + usuario("CLIENTE")))
                .andExpect(status().isForbidden());
    }

    // ---------- tokens de servicio ----------

    @Test
    void canalRegistraDevolucionesYReclamosConSuScope() throws Exception {
        pasa(mvc.perform(post("/api/v2/devoluciones").header("Authorization", "Bearer " + servicio("postventa:devoluciones:escribir"))));
        pasa(mvc.perform(post("/api/v2/reclamos").header("Authorization", "Bearer " + servicio("postventa:reclamos:escribir"))));
    }

    @Test
    void reembolsosSoloM1OGestor() throws Exception {
        pasa(mvc.perform(post("/api/v2/reembolsos").header("Authorization", "Bearer " + servicio("postventa:reembolsos:procesar"))));
        pasa(mvc.perform(post("/api/v2/reembolsos").header("Authorization", "Bearer " + usuario("ADMIN_VENTAS"))));
        mvc.perform(post("/api/v2/reembolsos").header("Authorization", "Bearer " + usuario("CLIENTE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unaAnulacionSoloPuedeSerSolicitadaPorM1() throws Exception {
        mvc.perform(post("/api/v2/reembolsos")
                        .header("Authorization", "Bearer " + usuario("ADMIN_VENTAS"))
                        .header("X-Idempotency-Key", "clave-anulacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origen\":\"ANULACION\",\"origenId\":\"ANUL-2026-12\",\"referenciaId\":\"PED-2026-981\",\"monto\":129.90,\"moneda\":\"PEN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    void origenDeReembolsoFueraDelCatalogoResponde403() throws Exception {
        mvc.perform(post("/api/v2/reembolsos")
                        .header("Authorization", "Bearer " + servicio("postventa:reembolsos:procesar"))
                        .header("X-Idempotency-Key", "clave-origen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origen\":\"DESPACHO_FALLIDO\",\"origenId\":\"DESP-2026-1\",\"referenciaId\":\"PED-2026-981\",\"monto\":10.00,\"moneda\":\"PEN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ORIGEN_NO_PERMITIDO"));
    }

    @Test
    void devolucionesF3SoloConElRolOScopeQueCorresponde() throws Exception {
        String cliente = "Bearer " + usuario("CLIENTE");
        String gestor = "Bearer " + usuario("ADMIN_VENTAS");
        String canal = "Bearer " + servicio("postventa:devoluciones:escribir");
        // El cliente registra y consulta lo suyo; el Gestor consulta, lista y resuelve
        pasa(mvc.perform(post("/api/v2/devoluciones").header("Authorization", cliente)));
        pasa(mvc.perform(post("/api/v2/devoluciones/evidencias/upload").header("Authorization", canal)));
        pasa(mvc.perform(get("/api/v2/devoluciones/DEV-2026-0001").header("Authorization", cliente)));
        pasa(mvc.perform(get("/api/v2/devoluciones/DEV-2026-0001").header("Authorization", gestor)));
        pasa(mvc.perform(get("/api/v2/devoluciones").param("clienteId", "11111111-1111-1111-1111-111111111111")
                .header("Authorization", gestor)));
        // Nadie más resuelve ni lista por cliente
        mvc.perform(patch("/api/v2/devoluciones/DEV-2026-0001/resolucion").header("Authorization", cliente))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v2/devoluciones/DEV-2026-0001/resolucion").header("Authorization", canal))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v2/devoluciones").param("clienteId", "11111111-1111-1111-1111-111111111111")
                        .header("Authorization", cliente))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v2/devoluciones/DEV-2026-0001").header("Authorization", canal))
                .andExpect(status().isForbidden());
    }

    @Test
    void reclamosF6SoloConElRolOScopeQueCorresponde() throws Exception {
        String cliente = "Bearer " + usuario("CLIENTE");
        String gestor = "Bearer " + usuario("ADMIN_VENTAS");
        String canal = "Bearer " + servicio("postventa:reclamos:escribir");
        // El cliente registra y consulta lo suyo; el Gestor consulta, lista y responde
        pasa(mvc.perform(post("/api/v2/reclamos").header("Authorization", cliente)));
        pasa(mvc.perform(get("/api/v2/reclamos/REC-2026-0001").header("Authorization", cliente)));
        pasa(mvc.perform(get("/api/v2/reclamos/REC-2026-0001").header("Authorization", gestor)));
        pasa(mvc.perform(patch("/api/v2/reclamos/REC-2026-0001/respuesta").header("Authorization", gestor)));
        // Nadie más responde ni lista
        mvc.perform(patch("/api/v2/reclamos/REC-2026-0001/respuesta").header("Authorization", cliente))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v2/reclamos/REC-2026-0001/respuesta").header("Authorization", canal))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v2/reclamos").header("Authorization", cliente))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v2/reclamos/REC-2026-0001").header("Authorization", canal))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v2/dashboard/metricas").header("Authorization", canal))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(get("/api/v2/devoluciones/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"));
    }

    @Test
    void tokenDeServicioDeOtraApiRecibe401() throws Exception {
        mvc.perform(post("/api/v2/reembolsos").header("Authorization", "Bearer " + servicio("postventa:reembolsos:procesar", "api-ventas")))
                .andExpect(status().isUnauthorized());
    }

    /** La seguridad dejó pasar: lo que responda el controller (o 404 si aún no existe) ya no es 401 ni 403. */
    private static void pasa(ResultActions r) throws Exception {
        r.andExpect(res -> assertThat(res.getResponse().getStatus()).isNotIn(401, 403));
    }

    private String usuario(String rol) {
        Instant ahora = Instant.now();
        return firmar(JwtClaimsSet.builder()
                .issuer(ISSUER).subject("11111111-1111-1111-1111-111111111111")
                .issuedAt(ahora).expiresAt(ahora.plusSeconds(900))
                .id(UUID.randomUUID().toString())
                .claims(c -> c.putAll(Map.of("tipo", "acceso", "roles", List.of(rol), "permisos", List.of())))
                .build());
    }

    private String servicio(String scope) {
        return servicio(scope, "api-postventa");
    }

    private String servicio(String scope, String audiencia) {
        Instant ahora = Instant.now();
        return firmar(JwtClaimsSet.builder()
                .issuer(ISSUER).subject("modulo-ventas").audience(List.of(audiencia))
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

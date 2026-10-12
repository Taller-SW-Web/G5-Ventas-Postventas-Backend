package ventas.pedido;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@WebMvcTest(controllers = PedidoController.class, properties = {
        "seguridad.habilitada=true",
        "seguridad.issuer=" + MisPedidosSeguridadTest.ISSUER,
        "seguridad.jwks-url=http://localhost:1/no-se-usa-en-pruebas"
})
@Import({SecurityConfig.class, MisPedidosSeguridadTest.Claves.class})
class MisPedidosSeguridadTest {

    static final String ISSUER = "http://localhost:8080/api/v1/auth";
    static final UUID CLIENTE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final KeyPair CLAVES = generar();

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder firmador;
    @MockitoBean PedidoService pedidoService;
    @MockitoBean CrearPedidoService crearPedidoService;

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

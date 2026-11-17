package gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * Prueba de integración del API Management: gateway real (Spring Cloud Gateway + Spring Security) delante de
 * un microservicio simulado (servidor HTTP del JDK) que además publica un JWKS propio, como el de G7.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiManagementIntegracionTest {

    private static final String EMISOR = "https://emisor-pruebas.local/api/v1/auth";
    private static final KeyPair LLAVES = generarLlaves();
    private static final HttpServer BACKEND = iniciarBackend();
    /** Cabeceras que recibió el microservicio simulado en la última llamada. */
    private static final Map<String, String> RECIBIDO = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + BACKEND.getAddress().getPort();
        r.add("VENTAS_URL", () -> base);
        r.add("POSTVENTA_URL", () -> base);
        r.add("seguridad.habilitada", () -> "true");
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> EMISOR);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> base + "/jwks");
        r.add("api-management.rate-limit.usuario", () -> "3");
        r.add("api-management.rate-limit.servicio", () -> "5");
    }

    @Autowired
    WebTestClient cliente;

    @BeforeEach
    void limpiar() {
        RECIBIDO.clear();
    }

    @AfterAll
    static void detener() {
        BACKEND.stop(0);
    }

    @Test
    void sin_token_responde_401_sin_llegar_al_servicio_y_con_correlacion() {
        cliente.get().uri("/api/v1/pedidos")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Correlation-Id");
        assertThat(RECIBIDO).isEmpty();
    }

    @Test
    void token_de_otro_emisor_se_rechaza() {
        cliente.get().uri("/api/v1/pedidos")
                .header("Authorization", "Bearer " + token("usuario-x", Map.of(), "https://otro-emisor"))
                .exchange()
                .expectStatus().isUnauthorized();
        assertThat(RECIBIDO).isEmpty();
    }

    @Test
    void token_valido_se_enruta_y_propaga_la_correlacion_recibida() {
        cliente.get().uri("/api/v2/devoluciones")
                .header("Authorization", "Bearer " + token("cliente-1", Map.of(), EMISOR))
                .header("X-Correlation-Id", "prueba-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "prueba-123")
                .expectHeader().valueEquals("X-RateLimit-Limit", "3")
                .expectHeader().exists("X-RateLimit-Remaining");

        assertThat(RECIBIDO).containsEntry("ruta", "/api/v2/devoluciones");
        assertThat(RECIBIDO).containsEntry("x-correlation-id", "prueba-123");
        assertThat(RECIBIDO).containsKey("authorization");   // el token llega a M1/M2 para revisar scopes
    }

    @Test
    void correlacion_invalida_se_reemplaza_por_un_uuid() {
        cliente.get().uri("/api/v1/pedidos")
                .header("Authorization", "Bearer " + token("cliente-2", Map.of(), EMISOR))
                .header("X-Correlation-Id", "<script>alert(1)</script> OR 1=1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("X-Correlation-Id",
                        v -> assertThat(v).matches("[0-9a-f-]{36}"));
    }

    @Test
    void usuario_que_supera_su_cuota_recibe_429_con_retry_after() {
        String token = token("cliente-cuota", Map.of(), EMISOR);
        for (int i = 0; i < 3; i++) {
            cliente.get().uri("/api/v1/pedidos").header("Authorization", "Bearer " + token)
                    .exchange().expectStatus().isOk();
        }
        RECIBIDO.clear();

        cliente.get().uri("/api/v1/pedidos").header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("Retry-After")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectBody().jsonPath("$.codigo").isEqualTo("LIMITE_SOLICITUDES");
        assertThat(RECIBIDO).as("el 429 no llega al microservicio").isEmpty();

        // Otro consumidor tiene su propia cuota
        cliente.get().uri("/api/v1/pedidos")
                .header("Authorization", "Bearer " + token("otro-cliente", Map.of(), EMISOR))
                .exchange().expectStatus().isOk();
    }

    @Test
    void token_tecnico_usa_la_cuota_de_servicio_por_client_id() {
        String token = token("modulo-marketplace",
                Map.of("tipo", "servicio", "client_id", "modulo-marketplace"), EMISOR);
        cliente.get().uri("/api/v1/pedidos").header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Limit", "5");
    }

    @Test
    void health_y_catalogo_openapi_son_publicos() {
        cliente.get().uri("/actuator/health").exchange().expectStatus().isOk();
        cliente.get().uri("/openapi/ventas.yaml").exchange().expectStatus().isOk();
        assertThat(RECIBIDO).containsEntry("ruta", "/openapi/ventas.yaml");
    }

    @Test
    void rutas_que_no_son_de_ventas_ni_postventa_no_se_exponen() {
        cliente.get().uri("/api/v1/otra-cosa")
                .header("Authorization", "Bearer " + token("cliente-3", Map.of(), EMISOR))
                .exchange()
                .expectStatus().isNotFound();
        assertThat(RECIBIDO).isEmpty();
    }

    // ---- utilidades ----

    private static String token(String sub, Map<String, Object> claims, String emisor) {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                    .subject(sub)
                    .issuer(emisor)
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(600)));
            claims.forEach(b::claim);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("pruebas").build(), b.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) LLAVES.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generarLlaves() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer iniciarBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            String jwks = new JWKSet(new RSAKey.Builder((RSAPublicKey) LLAVES.getPublic())
                    .keyID("pruebas").build()).toString();
            server.createContext("/jwks", ex -> responder(ex, jwks));
            server.createContext("/", ex -> {
                RECIBIDO.put("ruta", ex.getRequestURI().getPath());
                ex.getRequestHeaders().forEach((k, v) -> RECIBIDO.put(k.toLowerCase(), String.join(",", v)));
                responder(ex, "{\"ok\":true}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void responder(com.sun.net.httpserver.HttpExchange ex, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().put("Content-Type", List.of("application/json"));
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}

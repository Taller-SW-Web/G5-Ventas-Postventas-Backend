package ventas.compartido;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Seguridad de M1 según el acuerdo A10 con Seguridad (G7) y AGENTS.md puntos 9 y 11.
 *
 * - Token de usuario (tipo "acceso"): se autoriza por el claim roles -> ROLE_CLIENTE, ROLE_ADMIN_VENTAS.
 *   "Gestor" es ADMIN_VENTAS. ventas:pedidos:leer y ventas:admin NO son scopes.
 * - Token de servicio (tipo "servicio", aud api-ventas): se autoriza por el claim scope -> SCOPE_ventas:...
 *
 * Con seguridad.habilitada=false todo queda abierto (solo para desarrollo local).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${seguridad.habilitada:true}")
    private boolean habilitada;

    @Value("${seguridad.cors-origenes:http://localhost:5173}")
    private String[] origenesCors;

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain filtros(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!habilitada) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // 1.4b: historial del cliente autenticado (acuerdo H-03), token de usuario
                .requestMatchers(HttpMethod.GET, "/api/v1/pedidos/me").hasRole("CLIENTE")
                // Bandeja del Gestor (F2)
                .requestMatchers(HttpMethod.GET, "/api/v1/pedidos/anulaciones/**").hasRole("ADMIN_VENTAS")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/pedidos/*/anulaciones/*").hasRole("ADMIN_VENTAS")
                // Tokens de servicio (A10)
                .requestMatchers(HttpMethod.POST, "/api/v1/pedidos").hasAuthority("SCOPE_ventas:pedidos:crear")
                .requestMatchers(HttpMethod.POST, "/api/v1/pedidos/*/pagos/notificacion").hasAuthority("SCOPE_ventas:pagos:webhook")
                // 1.5: api-ventas pide token_admin_o_servicio -> Logística con su scope o el Gestor
                .requestMatchers(HttpMethod.PATCH, "/api/v1/pedidos/*/estado")
                    .hasAnyAuthority("SCOPE_ventas:pedidos:estado", "ROLE_ADMIN_VENTAS")
                .requestMatchers(HttpMethod.POST, "/api/v1/pedidos/*/eventos-logistica").hasAuthority("SCOPE_ventas:pedidos:estado")
                .requestMatchers(HttpMethod.POST, "/api/v1/pedidos/*/anulaciones")
                    .hasAnyAuthority("SCOPE_ventas:pedidos:anular", "ROLE_ADMIN_VENTAS")
                // Lecturas con token de usuario
                .requestMatchers(HttpMethod.GET, "/api/v1/pedidos").hasRole("ADMIN_VENTAS")
                // 1.3: cliente (solo sus pedidos), Gestor o M2 (modulo-postventa) con su scope; el scope NO sirve para listar
                .requestMatchers(HttpMethod.GET, "/api/v1/pedidos/*")
                    .hasAnyAuthority("ROLE_CLIENTE", "ROLE_ADMIN_VENTAS", "SCOPE_ventas:pedidos:consultar")
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o
                .jwt(j -> j.jwtAuthenticationConverter(convertidorAutoridades()))
                .authenticationEntryPoint((req, res, ex) -> escribir(res, HttpServletResponse.SC_UNAUTHORIZED,
                        ErrorResponse.de("TOKEN_INVALIDO", "Token ausente, vencido o inválido")))
                .accessDeniedHandler((req, res, ex) -> escribir(res, HttpServletResponse.SC_FORBIDDEN,
                        ErrorResponse.de("ACCESO_DENEGADO", "El token no tiene el rol o el permiso requerido"))))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> escribir(res, HttpServletResponse.SC_UNAUTHORIZED,
                        ErrorResponse.de("TOKEN_INVALIDO", "Token ausente, vencido o inválido")))
                .accessDeniedHandler((req, res, ex) -> escribir(res, HttpServletResponse.SC_FORBIDDEN,
                        ErrorResponse.de("ACCESO_DENEGADO", "El token no tiene el rol o el permiso requerido"))));
        return http.build();
    }

    /**
     * Valida firma RS256 con el JWKS de Seguridad, emisor exacto, vigencia y perfil del token.
     * No usa issuer-uri de Spring para no llamar al descubrimiento al arrancar.
     */
    @Bean
    @ConditionalOnProperty(name = "seguridad.habilitada", havingValue = "true", matchIfMissing = true)
    JwtDecoder jwtDecoder(@Value("${seguridad.issuer}") String issuer,
                          @Value("${seguridad.jwks-url}") String jwksUrl,
                          @Value("${seguridad.audiencia:api-ventas}") String audiencia) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUrl)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer),
                new ValidadorToken(audiencia)));
        return decoder;
    }

    /** roles -> ROLE_<rol> (token de usuario); scope -> SCOPE_<scope> (token de servicio). */
    static JwtAuthenticationConverter convertidorAutoridades() {
        JwtAuthenticationConverter convertidor = new JwtAuthenticationConverter();
        convertidor.setJwtGrantedAuthoritiesConverter(SecurityConfig::autoridades);
        return convertidor;
    }

    static Collection<GrantedAuthority> autoridades(Jwt jwt) {
        List<GrantedAuthority> autoridades = new ArrayList<>();
        String tipo = jwt.getClaimAsString("tipo");
        if (ValidadorToken.TIPO_USUARIO.equals(tipo)) {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(r -> autoridades.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
        } else if (ValidadorToken.TIPO_SERVICIO.equals(tipo)) {
            Object scope = jwt.getClaims().get("scope");
            List<String> scopes = scope instanceof Collection<?> lista
                    ? lista.stream().map(String::valueOf).toList()
                    : scope == null ? List.of() : Arrays.asList(String.valueOf(scope).trim().split("\\s+"));
            scopes.stream().filter(s -> !s.isBlank())
                    .forEach(s -> autoridades.add(new SimpleGrantedAuthority("SCOPE_" + s)));
        }
        return autoridades;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origenesCors).map(String::trim).filter(o -> !o.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private void escribir(HttpServletResponse res, int estado, ErrorResponse cuerpo) throws IOException {
        res.setStatus(estado);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(res.getOutputStream(), cuerpo);
    }
}

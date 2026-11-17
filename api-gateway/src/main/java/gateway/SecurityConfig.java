package gateway;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Autenticación centralizada del API Management: el gateway exige un JWT válido del emisor G7 (firma RS256
 * contra el JWKS, emisor y vigencia) antes de enrutar. Los scopes, la audiencia y el canal los revisa cada
 * microservicio. En Render: SEGURIDAD_HABILITADA=true, SECURITY_ISSUER y SECURITY_JWKS_URL.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${seguridad.habilitada:true}")
    private boolean habilitada;

    @Value("${CORS_ORIGINS:http://localhost:5173}")
    private String origenesCors;

    @Bean
    SecurityWebFilterChain filtros(ServerHttpSecurity http) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(Customizer.withDefaults());

        if (!habilitada) {
            http.authorizeExchange(a -> a.anyExchange().permitAll());
            return http.build();
        }

        http.authorizeExchange(a -> a
                .pathMatchers(HttpMethod.OPTIONS).permitAll()
                .pathMatchers("/actuator/health").permitAll()
                // Catálogo de APIs (contratos OpenAPI de M1 y M2) publicado por el gateway
                .pathMatchers(HttpMethod.GET, "/openapi/**").permitAll()
                .anyExchange().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origenes = Arrays.stream(origenesCors.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .toList();
        config.setAllowedOrigins(origenes);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        // El front puede leer la correlación y la cuota restante (API Management)
        config.setExposedHeaders(List.of("X-Correlation-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                "Retry-After"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

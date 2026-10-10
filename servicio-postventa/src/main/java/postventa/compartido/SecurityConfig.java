package postventa.compartido;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Scopes de M2 (sección 12.2 de la guía). */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${seguridad.habilitada:true}")
    private boolean habilitada;

    @Bean
    SecurityFilterChain filtros(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!habilitada) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.PATCH, "/api/v2/devoluciones/*/resolucion").hasAuthority("SCOPE_ventas:admin")
                .requestMatchers(HttpMethod.POST,  "/api/v2/devoluciones/**").hasAuthority("SCOPE_postventa:devoluciones:escribir")
                .requestMatchers(HttpMethod.GET,   "/api/v2/devoluciones/**").hasAnyAuthority("SCOPE_postventa:devoluciones:leer", "SCOPE_ventas:admin")
                .requestMatchers(HttpMethod.POST,  "/api/v2/reembolsos").hasAnyAuthority("SCOPE_postventa:reembolsos:procesar", "SCOPE_ventas:admin")
                .requestMatchers(HttpMethod.POST,  "/api/v2/csat").hasAuthority("SCOPE_postventa:csat:escribir")
                .requestMatchers(HttpMethod.PATCH, "/api/v2/reclamos/*/respuesta").hasAuthority("SCOPE_ventas:admin")
                .requestMatchers(HttpMethod.POST,  "/api/v2/reclamos").hasAuthority("SCOPE_postventa:reclamos:escribir")
                .requestMatchers(HttpMethod.GET,   "/api/v2/reclamos/**").hasAnyAuthority("SCOPE_postventa:reclamos:leer", "SCOPE_ventas:admin")
                .requestMatchers(HttpMethod.GET,   "/api/v2/dashboard/**").hasAuthority("SCOPE_ventas:admin")
                .requestMatchers(HttpMethod.POST,  "/api/v2/eventos").hasAuthority("SCOPE_postventa:reembolsos:procesar")
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }
}

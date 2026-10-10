package ventas.compartido;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

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
                .requestMatchers(HttpMethod.POST,  "/api/v1/pedidos").hasAuthority("SCOPE_ventas:pedidos:escribir")
                .requestMatchers(HttpMethod.POST,  "/api/v1/pedidos/*/pagos/notificacion").hasAuthority("SCOPE_ventas:pagos:webhook")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/pedidos/*/estado").hasAuthority("SCOPE_ventas:pedidos:estado")
                .requestMatchers(HttpMethod.POST,  "/api/v1/pedidos/*/eventos-logistica").hasAuthority("SCOPE_ventas:pedidos:estado")
                .requestMatchers("/api/v1/pedidos/anulaciones/**").hasAuthority("SCOPE_ventas:admin")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/pedidos/*/anulaciones/*").hasAuthority("SCOPE_ventas:admin")
                .requestMatchers(HttpMethod.POST,  "/api/v1/pedidos/*/anulaciones").hasAnyAuthority("SCOPE_ventas:pedidos:anular", "SCOPE_ventas:admin")
                .requestMatchers(HttpMethod.GET,   "/api/v1/pedidos/**").hasAnyAuthority("SCOPE_ventas:pedidos:leer", "SCOPE_ventas:admin")
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }
}

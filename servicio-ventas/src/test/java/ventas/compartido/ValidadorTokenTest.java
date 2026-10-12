package ventas.compartido;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class ValidadorTokenTest {

    private final ValidadorToken validador = new ValidadorToken("api-ventas");

    @Test
    void aceptaTokenDeUsuarioCliente() {
        assertThat(validador.validate(usuario("11111111-1111-1111-1111-111111111111", List.of("CLIENTE"))).hasErrors())
                .isFalse();
    }

    @Test
    void rechazaUsuarioConSubQueNoEsUuid() {
        assertThat(validador.validate(usuario("CLI-8841", List.of("CLIENTE"))).hasErrors()).isTrue();
    }

    @Test
    void rechazaUsuarioSinRoles() {
        Jwt jwt = base().claim("tipo", "acceso").subject("11111111-1111-1111-1111-111111111111")
                .claim("jti", "abc").build();
        assertThat(validador.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void aceptaServicioConAudienciaDeVentas() {
        Jwt jwt = base().claim("tipo", "servicio").subject("modulo-marketplace")
                .audience(List.of("api-ventas", "api-postventa")).claim("scope", "ventas:pedidos:crear").build();
        assertThat(validador.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void rechazaServicioDeOtraApi() {
        Jwt jwt = base().claim("tipo", "servicio").subject("modulo-marketplace")
                .audience(List.of("api-despacho")).claim("scope", "despachos:crear").build();
        assertThat(validador.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void rechazaTipoDesconocido() {
        Jwt jwt = base().claim("tipo", "refresh").subject("11111111-1111-1111-1111-111111111111").build();
        assertThat(validador.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void rolesYScopesSeConviertenEnAutoridades() {
        assertThat(nombres(SecurityConfig.autoridades(usuario("11111111-1111-1111-1111-111111111111",
                List.of("CLIENTE", "ADMIN_VENTAS")))))
                .containsExactlyInAnyOrder("ROLE_CLIENTE", "ROLE_ADMIN_VENTAS");

        Jwt servicio = base().claim("tipo", "servicio").subject("modulo-retail").audience(List.of("api-ventas"))
                .claim("scope", "ventas:pedidos:crear ventas:pedidos:anular").build();
        assertThat(nombres(SecurityConfig.autoridades(servicio)))
                .containsExactlyInAnyOrder("SCOPE_ventas:pedidos:crear", "SCOPE_ventas:pedidos:anular");
    }

    private static Jwt usuario(String sub, List<String> roles) {
        return base().claim("tipo", "acceso").subject(sub).claim("roles", roles).claim("jti", "j-1").build();
    }

    private static Jwt.Builder base() {
        Instant ahora = Instant.now();
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer("http://localhost:8080/api/v1/auth")
                .issuedAt(ahora).expiresAt(ahora.plusSeconds(900));
    }

    private static List<String> nombres(java.util.Collection<GrantedAuthority> a) {
        return a.stream().map(GrantedAuthority::getAuthority).toList();
    }
}

package ventas.compartido;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Revisa el perfil del token según el kit de integración de Seguridad (G7, §2 y SPEC-17).
 * La firma (RS256), el emisor (iss) y la vigencia (exp) los revisan otros validadores.
 *
 * Token de usuario: tipo "acceso", sub UUID, roles (lista de textos), jti, iat y exp. No trae aud.
 * Token de servicio: tipo "servicio", aud que incluye esta API (api-ventas) y scope, con exp.
 */
public class ValidadorToken implements OAuth2TokenValidator<Jwt> {

    static final String TIPO_USUARIO = "acceso";
    static final String TIPO_SERVICIO = "servicio";

    private static final Pattern UUID = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final String audiencia;

    public ValidadorToken(String audiencia) {
        this.audiencia = audiencia;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String tipo = jwt.getClaimAsString("tipo");
        boolean valido = TIPO_USUARIO.equals(tipo) ? usuarioValido(jwt)
                : TIPO_SERVICIO.equals(tipo) && servicioValido(jwt);
        return valido
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Token sin el perfil esperado", null));
    }

    private static boolean usuarioValido(Jwt jwt) {
        Object roles = jwt.getClaims().get("roles");
        Object jti = jwt.getClaims().get("jti");
        String sub = jwt.getSubject();
        return roles instanceof List<?> lista && lista.stream().allMatch(String.class::isInstance)
                && jti instanceof String texto && !texto.isBlank()
                && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                && sub != null && UUID.matcher(sub).matches();
    }

    private boolean servicioValido(Jwt jwt) {
        List<String> aud = jwt.getAudience();
        return jwt.getExpiresAt() != null
                && aud != null && aud.contains(audiencia)
                && jwt.getClaims().get("scope") != null;
    }
}

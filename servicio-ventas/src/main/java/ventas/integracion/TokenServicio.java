package ventas.integracion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Token de servicio de modulo-ventas (acuerdo A10 con Seguridad, kit de integración §4):
 * POST /api/v1/auth/token, application/x-www-form-urlencoded, grant_type=client_credentials.
 * Se pide uno por scope (cada scope pertenece a una API: postventa, productos o despacho) y se guarda
 * hasta un minuto antes de que venza. Sin client-secret configurado no pide nada: las llamadas salen sin token.
 * El secreto va en el .env (SECURITY_CLIENT_SECRET), nunca en el código. En local: secreto-de-prueba.
 */
@Slf4j
@Component
public class TokenServicio {

    /** Antes de este margen se pide uno nuevo, para que no venza a mitad de una llamada. */
    private static final Duration MARGEN = Duration.ofMinutes(1);
    /** Seguridad emite tokens de 1 hora; se usa si la respuesta no trae expires_in. */
    private static final long VIGENCIA_POR_DEFECTO = 3600;

    private record Respuesta(@JsonProperty("access_token") @JsonAlias("accessToken") String accessToken,
                             @JsonProperty("expires_in") @JsonAlias("expiresIn") Long expiresIn) {}

    private record Guardado(String token, Instant vence) {}

    private final String clientId;
    private final String clientSecret;
    private final RestClient http;
    private final Clock reloj;
    private final Map<String, Guardado> guardados = new ConcurrentHashMap<>();

    @Autowired
    public TokenServicio(@Value("${seguridad.token-url:http://localhost:8080/api/v1/auth/token}") String url,
                         @Value("${seguridad.client-id:modulo-ventas}") String clientId,
                         @Value("${seguridad.client-secret:}") String clientSecret,
                         RestClient.Builder builder) {
        this(url, clientId, clientSecret, builder, Clock.systemUTC());
    }

    TokenServicio(String url, String clientId, String clientSecret, RestClient.Builder builder, Clock reloj) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.http = builder.baseUrl(url).build();
        this.reloj = reloj;
    }

    /** Para pruebas y para cuando no se quiere token: nunca pide nada. */
    public static TokenServicio sinCredenciales() {
        return new TokenServicio("http://localhost", "modulo-ventas", "", RestClient.builder(), Clock.systemUTC());
    }

    /** Pone "Authorization: Bearer ..." si hay token para ese scope; si no, no toca las cabeceras. */
    public Consumer<HttpHeaders> autorizacion(String scope) {
        return cabeceras -> token(scope).ifPresent(cabeceras::setBearerAuth);
    }

    /** Vacío si no hay secreto configurado o si Seguridad no respondió (la llamada sale sin token y fallará con 401). */
    public Optional<String> token(String scope) {
        if (clientSecret == null || clientSecret.isBlank()) {
            return Optional.empty();
        }
        Guardado g = guardados.get(scope);
        if (g != null && reloj.instant().isBefore(g.vence().minus(MARGEN))) {
            return Optional.of(g.token());
        }
        return pedir(scope);
    }

    /** Lo llaman los clientes cuando el destino responde 401: el siguiente intento pide un token nuevo. */
    public void invalidar(String scope) {
        guardados.remove(scope);
    }

    /** Atajo para los catch de los clientes: si el error fue 401, olvida el token de ese scope. */
    public void revisar(RestClientException e, String scope) {
        if (e instanceof HttpClientErrorException.Unauthorized) {
            invalidar(scope);
        }
    }

    private Optional<String> pedir(String scope) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("scope", scope);
        try {
            Respuesta r = http.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Respuesta.class);
            if (r == null || r.accessToken() == null) {
                log.error("Seguridad respondió sin access_token para el scope {}", scope);
                return Optional.empty();
            }
            long segundos = r.expiresIn() != null ? r.expiresIn() : VIGENCIA_POR_DEFECTO;
            guardados.put(scope, new Guardado(r.accessToken(), reloj.instant().plusSeconds(segundos)));
            return Optional.of(r.accessToken());
        } catch (RestClientException e) {
            log.error("No se pudo obtener el token de servicio para {}: {}", scope, e.getMessage());
            return Optional.empty();
        }
    }
}

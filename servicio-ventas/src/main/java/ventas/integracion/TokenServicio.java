package ventas.integracion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

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
 * Token de servicio de modulo-ventas (acuerdo A10 con Seguridad, kit §4):
 * POST /api/v1/auth/token, application/x-www-form-urlencoded, grant_type=client_credentials.
 * Sin scope ni audience (SPEC-17, RF-17.12): el token trae todos los scopes concedidos al cliente y su aud lista
 * las APIs dueñas, así que un solo token sirve para M2, Productos y Despacho. La respuesta va en camelCase
 * (accessToken, expiresIn). Se guarda hasta un minuto antes de que venza.
 * Sin client-secret configurado no pide nada y las llamadas salen sin token. El secreto va solo en el .env.
 */
@Slf4j
@Component
public class TokenServicio {

    /** Antes de este margen se pide uno nuevo, para que no venza a mitad de una llamada. */
    private static final Duration MARGEN = Duration.ofMinutes(1);
    /** Seguridad emite tokens de 1 hora; se usa si la respuesta no trae expiresIn. */
    private static final long VIGENCIA_POR_DEFECTO = 3600;

    private record Respuesta(String accessToken, Long expiresIn) {}

    private record Guardado(String token, Instant vence) {}

    private final String clientId;
    private final String clientSecret;
    private final RestClient http;
    private final Clock reloj;
    private volatile Guardado guardado;

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

    /** Pone "Authorization: Bearer ..." si hay token; si no, no toca las cabeceras. */
    public Consumer<HttpHeaders> autorizacion() {
        return cabeceras -> token().ifPresent(cabeceras::setBearerAuth);
    }

    /** Vacío si no hay secreto configurado o si Seguridad no respondió (la llamada sale sin token y fallará con 401). */
    public Optional<String> token() {
        if (clientSecret == null || clientSecret.isBlank()) {
            return Optional.empty();
        }
        Guardado g = guardado;
        if (g != null && reloj.instant().isBefore(g.vence().minus(MARGEN))) {
            return Optional.of(g.token());
        }
        return pedir();
    }

    /** Lo llaman los clientes en sus catch: si el destino respondió 401, el siguiente intento pide un token nuevo. */
    public void revisar(RestClientException e) {
        if (e instanceof HttpClientErrorException.Unauthorized) {
            guardado = null;
        }
    }

    private synchronized Optional<String> pedir() {
        Guardado g = guardado;
        if (g != null && reloj.instant().isBefore(g.vence().minus(MARGEN))) {
            return Optional.of(g.token());   // otro hilo ya lo pidió
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        try {
            Respuesta r = http.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Respuesta.class);
            if (r == null || r.accessToken() == null) {
                log.error("Seguridad respondió sin accessToken");
                return Optional.empty();
            }
            long segundos = r.expiresIn() != null ? r.expiresIn() : VIGENCIA_POR_DEFECTO;
            guardado = new Guardado(r.accessToken(), reloj.instant().plusSeconds(segundos));
            return Optional.of(r.accessToken());
        } catch (RestClientException e) {
            log.error("No se pudo obtener el token de servicio: {}", e.getMessage());
            return Optional.empty();
        }
    }
}

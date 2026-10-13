package postventa.integracion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import postventa.compartido.DependenciaNoDisponibleException;

/**
 * Token de servicio de modulo-postventa (client_credentials, kit de Seguridad §4 y §6), para llamar a otros
 * módulos con la identidad de M2 y no con la de quien hizo la petición.
 *
 * - POST {seguridad.token-url} con grant_type, client_id y client_secret. Sin scope ni audience: el token trae
 *   todos los scopes concedidos y su aud son las APIs dueñas de esos scopes (SPEC-17, RF-17.12).
 * - La respuesta viene en camelCase (accessToken, expiresIn, scopes).
 * - Se guarda y se pide uno nuevo un minuto antes de que venza.
 * - Con seguridad.habilitada=false no se pide nada (los demás servicios tampoco validan tokens).
 *
 * La credencial real llega en el Hito 4; mientras tanto, la de prueba del kit (secreto-de-prueba).
 */
@Slf4j
@Component
public class TokenServicio {

    static final Duration MARGEN = Duration.ofMinutes(1);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(String accessToken, long expiresIn, List<String> scopes) {}

    private final boolean habilitada;
    private final String clientId;
    private final String clientSecret;
    private final Clock reloj;
    private final RestClient http;

    private String token;
    private Instant venceEn = Instant.MIN;

    @Autowired
    public TokenServicio(@Value("${seguridad.habilitada:true}") boolean habilitada,
                         @Value("${seguridad.token-url:http://localhost:8080/api/v1/auth/token}") String url,
                         @Value("${seguridad.client-id:modulo-postventa}") String clientId,
                         @Value("${seguridad.client-secret:secreto-de-prueba}") String clientSecret) {
        this(habilitada, clientId, clientSecret, Clock.systemUTC(), RestClient.builder().baseUrl(url));
    }

    TokenServicio(boolean habilitada, String clientId, String clientSecret, Clock reloj, RestClient.Builder http) {
        this.habilitada = habilitada;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.reloj = reloj;
        this.http = http.build();
    }

    /** Bearer listo para la cabecera Authorization; vacío con la seguridad apagada. */
    public Optional<String> authorization() {
        return habilitada ? Optional.of("Bearer " + vigente()) : Optional.empty();
    }

    private synchronized String vigente() {
        if (token == null || !reloj.instant().isBefore(venceEn.minus(MARGEN))) {
            pedir();
        }
        return token;
    }

    private void pedir() {
        MultiValueMap<String, String> cuerpo = new LinkedMultiValueMap<>();
        cuerpo.add("grant_type", "client_credentials");
        cuerpo.add("client_id", clientId);
        cuerpo.add("client_secret", clientSecret);
        try {
            TokenResponse r = http.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(cuerpo)
                    .retrieve()
                    .body(TokenResponse.class);
            if (r == null || r.accessToken() == null || r.accessToken().isBlank()) {
                throw new DependenciaNoDisponibleException("SEGURIDAD_NO_DISPONIBLE",
                        "Seguridad no devolvió el token de servicio");
            }
            token = r.accessToken();
            venceEn = reloj.instant().plusSeconds(r.expiresIn());
            log.info("Token de servicio de {} renovado, vence en {} s, scopes {}", clientId, r.expiresIn(), r.scopes());
        } catch (RestClientException e) {
            throw new DependenciaNoDisponibleException("SEGURIDAD_NO_DISPONIBLE",
                    "No se pudo obtener el token de servicio de Seguridad", e);
        }
    }
}

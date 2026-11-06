package pasarela.cobro;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Token de servicio del simulador para avisar a M1 (scope ventas:pagos:webhook).
 *
 * Lo pide a Seguridad con client_credentials, igual que M1 y M2, y lo guarda
 * hasta un minuto antes de que venza. Sin secreto configurado no pide nada y
 * el aviso sale sin token, que es lo que se usa en local con seguridad apagada.
 */
@Component
public class TokenServicio {

    private static final Logger LOG =
            LoggerFactory.getLogger(TokenServicio.class);

    private static final Duration MARGEN = Duration.ofMinutes(1);
    private static final long VIGENCIA_POR_DEFECTO = 3600;

    // Seguridad también corre en Render Free y puede tardar en despertar.
    private static final Duration TIEMPO_CONEXION = Duration.ofSeconds(5);
    private static final Duration TIEMPO_RESPUESTA = Duration.ofSeconds(60);

    private record Respuesta(String accessToken, Long expiresIn) {
    }

    private record Guardado(String token, Instant vence) {
    }

    private final String clientId;
    private final String clientSecret;
    private final RestClient http;
    private final Clock reloj;
    private volatile Guardado guardado;

    @Autowired
    public TokenServicio(
            @Value("${pasarela.token-url:http://localhost:8080/api/v1/auth/token}")
            String url,
            @Value("${pasarela.client-id:simulador-pasarela}")
            String clientId,
            @Value("${pasarela.client-secret:}")
            String clientSecret) {

        this(crearCliente(url), clientId, clientSecret, Clock.systemUTC());
    }

    TokenServicio(
            RestClient http,
            String clientId,
            String clientSecret,
            Clock reloj) {

        this.http = Objects.requireNonNull(http);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.reloj = Objects.requireNonNull(reloj);
    }

    /**
     * Vacío si no hay secreto o si Seguridad no respondió.
     * En ese caso M1 responderá 401 y el cobro usará sus reintentos.
     */
    public Optional<String> token() {
        if (clientSecret == null || clientSecret.isBlank()) {
            return Optional.empty();
        }

        Guardado actual = guardado;

        if (vigente(actual)) {
            return Optional.of(actual.token());
        }

        return pedir();
    }

    /**
     * M1 rechazó el token: el siguiente intento pedirá uno nuevo.
     */
    public void descartar() {
        guardado = null;
    }

    private synchronized Optional<String> pedir() {
        Guardado actual = guardado;

        if (vigente(actual)) {
            return Optional.of(actual.token());
        }

        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("grant_type", "client_credentials");
        formulario.add("client_id", clientId);
        formulario.add("client_secret", clientSecret);

        try {
            Respuesta respuesta = http.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formulario)
                    .retrieve()
                    .body(Respuesta.class);

            if (respuesta == null || respuesta.accessToken() == null) {
                LOG.error("Seguridad respondió sin accessToken");
                return Optional.empty();
            }

            long segundos = respuesta.expiresIn() != null
                    ? respuesta.expiresIn()
                    : VIGENCIA_POR_DEFECTO;

            guardado = new Guardado(
                    respuesta.accessToken(),
                    reloj.instant().plusSeconds(segundos));

            return Optional.of(respuesta.accessToken());
        } catch (RestClientException error) {
            LOG.error(
                    "No se pudo obtener el token de servicio: {}",
                    error.getMessage());

            return Optional.empty();
        }
    }

    private boolean vigente(Guardado candidato) {
        return candidato != null
                && reloj.instant().isBefore(candidato.vence().minus(MARGEN));
    }

    private static RestClient crearCliente(String url) {
        SimpleClientHttpRequestFactory fabrica =
                new SimpleClientHttpRequestFactory();

        fabrica.setConnectTimeout(TIEMPO_CONEXION);
        fabrica.setReadTimeout(TIEMPO_RESPUESTA);

        return RestClient.builder()
                .baseUrl(url)
                .requestFactory(fabrica)
                .build();
    }
}

package ventas.integracion.envios;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ventas.integracion.TokenServicio;

/**
 * Marketplace (G1): POST del evento pedidos.envio.estado_actualizado (1.10, acuerdo H-05).
 * URL en MARKETPLACE_WEBHOOK_URL. Autenticación (acuerdo con G1, 22/11): si está MARKETPLACE_WEBHOOK_TOKEN, se manda
 * el token propio de Marketplace en la cabecera MARKETPLACE_WEBHOOK_HEADER (por defecto Authorization, con "Bearer "
 * delante si el token no lo trae); si no, el token de servicio de modulo-ventas emitido por G7.
 * Nunca lanza excepción: devuelve qué pasó.
 * Con integracion.marketplace-simulado=true no llama a nadie.
 */
@Slf4j
@Component
public class MarketplaceClient {

    /** ACEPTADO: 2xx. RECHAZADO: 400/409/422, reintentar no sirve. NO_DISPONIBLE: caído, 5xx, 401/403/404. */
    public enum Envio { ACEPTADO, RECHAZADO, NO_DISPONIBLE, SIMULADO }

    private final boolean simulado;
    private final RestClient http;
    private final TokenServicio tokens;
    private final String cabecera;
    private final String token;

    public MarketplaceClient(boolean simulado, String url, RestClient.Builder builder, TokenServicio tokens) {
        this(simulado, url, builder, tokens, "", "");
    }

    @Autowired
    public MarketplaceClient(@Value("${integracion.marketplace-simulado:${integracion.simulado:true}}") boolean simulado,
                             @Value("${integracion.marketplace-webhook-url:http://localhost:8095/api/v1/webhooks/ventas/envios}") String url,
                             RestClient.Builder builder,
                             TokenServicio tokens,
                             @Value("${integracion.marketplace-webhook-header:Authorization}") String cabecera,
                             @Value("${integracion.marketplace-webhook-token:}") String token) {
        this.simulado = simulado;
        this.http = builder.baseUrl(url).build();
        this.tokens = tokens;
        this.cabecera = cabecera == null || cabecera.isBlank() ? HttpHeaders.AUTHORIZATION : cabecera.trim();
        this.token = token == null ? "" : token.trim();
    }

    /** Token propio de Marketplace si está configurado; si no, el token de servicio de G7. */
    private void autenticar(HttpHeaders h) {
        if (token.isEmpty()) {
            tokens.autorizacion().accept(h);
            return;
        }
        boolean authorization = HttpHeaders.AUTHORIZATION.equalsIgnoreCase(cabecera);
        h.set(cabecera, authorization && !token.regionMatches(true, 0, "Bearer ", 0, 7) ? "Bearer " + token : token);
    }

    public Envio notificar(UUID idEvento, String payloadJson) {
        if (simulado) {
            log.info("[simulado] aviso de envío {} a Marketplace", idEvento);
            return Envio.SIMULADO;
        }
        try {
            http.post()
                    .headers(this::autenticar)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payloadJson)
                    .retrieve()
                    .toBodilessEntity();
            return Envio.ACEPTADO;
        } catch (RestClientException e) {
            if (token.isEmpty()) {
                tokens.revisar(e);
            }
            Envio envio = e instanceof HttpClientErrorException c && (c.getStatusCode() == HttpStatus.BAD_REQUEST
                    || c.getStatusCode() == HttpStatus.CONFLICT || c.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY)
                    ? Envio.RECHAZADO : Envio.NO_DISPONIBLE;
            log.warn("Aviso de envío {} a Marketplace: {} ({})", idEvento, envio, e.getMessage());
            return envio;
        }
    }
}

package ventas.integracion.envios;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ventas.integracion.TokenServicio;

/**
 * Marketplace (G1): POST del evento pedidos.envio.estado_actualizado (1.10, acuerdo H-05).
 * La URL y la autenticación están por confirmar con Marketplace: por ahora la URL va en MARKETPLACE_WEBHOOK_URL
 * y se manda el token de servicio de modulo-ventas. Nunca lanza excepción: devuelve qué pasó.
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

    public MarketplaceClient(@Value("${integracion.marketplace-simulado:${integracion.simulado:true}}") boolean simulado,
                             @Value("${integracion.marketplace-webhook-url:http://localhost:8095/api/v1/webhooks/ventas/envios}") String url,
                             RestClient.Builder builder,
                             TokenServicio tokens) {
        this.simulado = simulado;
        this.http = builder.baseUrl(url).build();
        this.tokens = tokens;
    }

    public Envio notificar(UUID idEvento, String payloadJson) {
        if (simulado) {
            log.info("[simulado] aviso de envío {} a Marketplace", idEvento);
            return Envio.SIMULADO;
        }
        try {
            http.post()
                    .headers(tokens.autorizacion())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payloadJson)
                    .retrieve()
                    .toBodilessEntity();
            return Envio.ACEPTADO;
        } catch (RestClientException e) {
            tokens.revisar(e);
            Envio envio = e instanceof HttpClientErrorException c && (c.getStatusCode() == HttpStatus.BAD_REQUEST
                    || c.getStatusCode() == HttpStatus.CONFLICT || c.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY)
                    ? Envio.RECHAZADO : Envio.NO_DISPONIBLE;
            log.warn("Aviso de envío {} a Marketplace: {} ({})", idEvento, envio, e.getMessage());
            return envio;
        }
    }
}

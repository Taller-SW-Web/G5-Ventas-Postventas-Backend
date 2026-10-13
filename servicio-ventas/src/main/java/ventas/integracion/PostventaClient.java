package ventas.integracion;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Llamadas de M1 a M2. Nunca lanzan excepción: devuelven qué pasó para que el llamador decida si reintenta.
 * - POST /api/v2/reembolsos (2.6): reembolso de una anulación. X-Idempotency-Key fija por anulación,
 *   así un reintento nunca paga dos veces.
 * - POST /api/v2/eventos (2.13): eventos del outbox (pedido.pagado, .anulado, .entregado).
 * Con integracion.postventa-simulado=true no llama a M2: solo deja un log y responde SIMULADO.
 * Pendiente: token de servicio de modulo-ventas (commit del token), credenciales del Hito 4.
 */
@Slf4j
@Component
public class PostventaClient {

    /**
     * ACEPTADO: M2 respondió 2xx. RECHAZADO: M2 dijo que la solicitud está mal (400/409), reintentar no sirve.
     * NO_DISPONIBLE: M2 caído, 5xx, 401/403/404: se puede reintentar. SIMULADO: no se llamó a M2.
     */
    public enum Envio { ACEPTADO, RECHAZADO, NO_DISPONIBLE, SIMULADO }

    private record ReembolsoRequest(String origen, String origenId, String referenciaId, BigDecimal monto,
                                    String moneda, String motivo, String solicitadoPor) {}

    /** Lo que M2 devuelve en el 201 de 2.6 (solo los campos que usa M1). */
    private record ReembolsoRegistrado(String reembolsoId, String estado) {}

    /** reembolsoId y estado vienen de M2 solo si envio == ACEPTADO; detalle explica el fallo. */
    public record RespuestaReembolso(Envio envio, String reembolsoId, String estado, String detalle) {

        static RespuestaReembolso sin(Envio envio, String detalle) {
            return new RespuestaReembolso(envio, null, null, detalle);
        }
    }

    private final boolean simulado;
    private final RestClient http;

    public PostventaClient(@Value("${integracion.postventa-simulado:${integracion.simulado:true}}") boolean simulado,
                           @Value("${integracion.postventa-url:http://localhost:8082}") String url,
                           RestClient.Builder builder) {
        this.simulado = simulado;
        this.http = builder.baseUrl(url).build();
    }

    public RespuestaReembolso solicitarReembolso(String anulacionId, String pedidoId, BigDecimal monto,
                                                 String moneda, String motivo) {
        String clave = claveIdempotencia(anulacionId);
        if (simulado) {
            log.info("[simulado] reembolso de {} ({}) por {} {}, clave {}", pedidoId, anulacionId, monto, moneda, clave);
            return RespuestaReembolso.sin(Envio.SIMULADO, null);
        }
        try {
            ReembolsoRegistrado r = http.post().uri("/api/v2/reembolsos")
                    .header("X-Idempotency-Key", clave)
                    .body(new ReembolsoRequest("ANULACION", anulacionId, pedidoId, monto, moneda, motivo, "SISTEMA_M1"))
                    .retrieve()
                    .body(ReembolsoRegistrado.class);
            return r == null ? new RespuestaReembolso(Envio.ACEPTADO, null, null, null)
                    : new RespuestaReembolso(Envio.ACEPTADO, r.reembolsoId(), r.estado(), null);
        } catch (RestClientException e) {
            Envio envio = clasificar(e);
            log.error("Reembolso de {} ({}) a M2: {} ({})", pedidoId, anulacionId, envio, e.getMessage());
            return RespuestaReembolso.sin(envio, recortar(e.getMessage()));
        }
    }

    /** idEvento solo es para el log: ya viaja dentro del payload y M2 lo usa para no procesarlo dos veces. */
    public Envio enviarEvento(UUID idEvento, String payloadJson) {
        if (simulado) {
            log.info("[simulado] evento {} a M2", idEvento);
            return Envio.SIMULADO;
        }
        try {
            http.post().uri("/api/v2/eventos")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payloadJson)
                    .retrieve()
                    .toBodilessEntity();
            return Envio.ACEPTADO;
        } catch (RestClientException e) {
            Envio envio = clasificar(e);
            log.warn("Evento {} a M2: {} ({})", idEvento, envio, e.getMessage());
            return envio;
        }
    }

    private static Envio clasificar(RestClientException e) {
        if (e instanceof HttpClientErrorException c
                && (c.getStatusCode() == HttpStatus.BAD_REQUEST || c.getStatusCode() == HttpStatus.CONFLICT)) {
            return Envio.RECHAZADO;
        }
        return Envio.NO_DISPONIBLE;
    }

    private static String recortar(String texto) {
        return texto == null || texto.length() <= 255 ? texto : texto.substring(0, 255);
    }

    static String claveIdempotencia(String anulacionId) {
        return UUID.nameUUIDFromBytes(("reembolso:" + anulacionId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}

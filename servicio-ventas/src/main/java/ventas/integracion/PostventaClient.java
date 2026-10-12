package ventas.integracion;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * M2: solicitud de reembolso de una anulación (POST /api/v2/reembolsos, api-postventas 2.6).
 * La clave X-Idempotency-Key se deriva del código de la anulación, así un reintento nunca paga dos veces.
 * Con integracion.simulado=true solo deja un log.
 * Pendiente: token de servicio de modulo-ventas (scope postventa:reembolsos:procesar), credenciales del Hito 4.
 */
@Slf4j
@Component
public class PostventaClient {

    private record ReembolsoRequest(String origen, String origenId, String referenciaId, BigDecimal monto,
                                    String moneda, String motivo, String solicitadoPor) {}

    private final boolean simulado;
    private final RestClient http;

    public PostventaClient(@Value("${integracion.simulado:true}") boolean simulado,
                           @Value("${integracion.postventa-url:http://localhost:8082}") String url) {
        this.simulado = simulado;
        this.http = RestClient.builder().baseUrl(url).build();
    }

    /** Nunca lanza excepción: si M2 no responde, queda en el log para reintentarlo. */
    public void solicitarReembolso(String anulacionId, String pedidoId, BigDecimal monto, String moneda, String motivo) {
        String clave = claveIdempotencia(anulacionId);
        if (simulado) {
            log.info("[simulado] reembolso de {} ({}) por {} {}, clave {}", pedidoId, anulacionId, monto, moneda, clave);
            return;
        }
        try {
            http.post().uri("/api/v2/reembolsos")
                    .header("X-Idempotency-Key", clave)
                    .body(new ReembolsoRequest("ANULACION", anulacionId, pedidoId, monto, moneda, motivo, "SISTEMA_M1"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("No se pudo pedir el reembolso de {} ({}) a M2: {}", pedidoId, anulacionId, e.getMessage());
        }
    }

    static String claveIdempotencia(String anulacionId) {
        return UUID.nameUUIDFromBytes(("reembolso:" + anulacionId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}

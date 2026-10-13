package ventas.integracion;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
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

    /** Lo que M2 devuelve en el 201 de 2.6 (solo los campos que usa M1). */
    public record ReembolsoRegistrado(String reembolsoId, String estado) {}

    private final boolean simulado;
    private final RestClient http;

    public PostventaClient(@Value("${integracion.simulado:true}") boolean simulado,
                           @Value("${integracion.postventa-url:http://localhost:8082}") String url,
                           RestClient.Builder builder) {
        this.simulado = simulado;
        this.http = builder.baseUrl(url).build();
    }

    /**
     * Nunca lanza excepción. Devuelve vacío en modo simulado o si M2 no respondió bien (queda en el log
     * para reintentarlo; como la clave es fija, reintentar no duplica el pago).
     */
    public Optional<ReembolsoRegistrado> solicitarReembolso(String anulacionId, String pedidoId, BigDecimal monto,
                                                            String moneda, String motivo) {
        String clave = claveIdempotencia(anulacionId);
        if (simulado) {
            log.info("[simulado] reembolso de {} ({}) por {} {}, clave {}", pedidoId, anulacionId, monto, moneda, clave);
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(http.post().uri("/api/v2/reembolsos")
                    .header("X-Idempotency-Key", clave)
                    .body(new ReembolsoRequest("ANULACION", anulacionId, pedidoId, monto, moneda, motivo, "SISTEMA_M1"))
                    .retrieve()
                    .body(ReembolsoRegistrado.class));
        } catch (RestClientException e) {
            log.error("No se pudo pedir el reembolso de {} ({}) a M2: {}", pedidoId, anulacionId, e.getMessage());
            return Optional.empty();
        }
    }

    static String claveIdempotencia(String anulacionId) {
        return UUID.nameUUIDFromBytes(("reembolso:" + anulacionId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}

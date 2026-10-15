package postventa.integracion;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import postventa.reembolso.EstadoReembolso;

/** Adaptador F4 para el contrato de extornos de la pasarela (POST /pasarela/v1/extornos). */
@Component
public class PasarelaClient {
    private final boolean simulada;
    private final RestClient http;

    public PasarelaClient(@Value("${integracion.pasarela-simulada:true}") boolean simulada,
                          @Value("${integracion.pasarela-url:http://localhost:8083}") String url,
                          @Value("${integracion.pasarela-timeout-ms:2000}") int timeoutMs) {
        this(simulada, RestClient.builder().baseUrl(url).requestFactory(fabrica(timeoutMs)).build());
    }

    PasarelaClient(boolean simulada, RestClient http) {
        this.simulada = simulada;
        this.http = http;
    }

    public ResultadoExtorno solicitarExtorno(String clave, String referencia, BigDecimal monto, String moneda) {
        if (simulada) return new ResultadoExtorno(EstadoReembolso.EXITOSO, "TX-SIM-" + UUID.randomUUID(), "Extorno confirmado");
        try {
            RespuestaExtorno respuesta = http.post().uri("/pasarela/v1/extornos")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Key", clave)
                    .body(new SolicitudExtorno(referencia, monto, moneda, null))
                    .retrieve().body(RespuestaExtorno.class);
            if (respuesta == null || respuesta.estado() == null)
                return new ResultadoExtorno(EstadoReembolso.FALLIDO, null, "La pasarela devolvió una respuesta incompleta");
            return new ResultadoExtorno(estado(respuesta.estado()), respuesta.idTransaccion(), respuesta.detalle());
        } catch (ResourceAccessException e) {
            // La pasarela puede haber procesado la operación aunque M2 no recibiera la respuesta.
            return new ResultadoExtorno(EstadoReembolso.PENDIENTE, null,
                    "Resultado incierto: no se recibió respuesta de la pasarela");
        } catch (RestClientResponseException e) {
            return new ResultadoExtorno(EstadoReembolso.FALLIDO, null,
                    "La pasarela rechazó el extorno (HTTP " + e.getStatusCode().value() + ")");
        }
    }

    private static SimpleClientHttpRequestFactory fabrica(int timeoutMs) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(timeoutMs);
        fabrica.setReadTimeout(timeoutMs);
        return fabrica;
    }

    private static EstadoReembolso estado(String estado) {
        try {
            return EstadoReembolso.valueOf(estado);
        } catch (IllegalArgumentException e) {
            return EstadoReembolso.FALLIDO;
        }
    }

    private record SolicitudExtorno(String referencia, BigDecimal monto, String moneda, String escenario) {}
    private record RespuestaExtorno(String idTransaccion, String estado, String detalle) {}
    public record ResultadoExtorno(EstadoReembolso estado, String transaccionId, String detalle) {}
}

package ventas.integracion;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Despacho (G2), contrato 7.1: POST /api/v1/despachos crea el despacho de un pedido ("paquete sellado").
 * Token de servicio de modulo-ventas con scope despachos:crear. Es uno por pedido: si ya existía responde 200,
 * así que reintentar es seguro (además va la Idempotency-Key fija por pedido, acuerdo D9).
 * Los errores vienen en application/problem+json con el campo code: se decide por status y code, nunca por el texto.
 * Con integracion.despacho-simulado=true no llama a nadie. La cancelación (7.2) es de F2.
 */
@Slf4j
@Component
public class DespachoClient {

    public static final String SIN_COBERTURA = "DESP_ERROR_SIN_COBERTURA";

    /**
     * CREADO (201) y YA_EXISTIA (200): Despacho tiene el despacho. SIN_COBERTURA: 422 DESP_ERROR_SIN_COBERTURA.
     * RECHAZADO: otro 4xx de negocio, reintentar no sirve. NO_DISPONIBLE: 503, 5xx, 401/403 o sin respuesta;
     * se puede reintentar. SIMULADO: no se llamó.
     */
    public enum Resultado { CREADO, YA_EXISTIA, SIN_COBERTURA, RECHAZADO, NO_DISPONIBLE, SIMULADO }

    public record Respuesta(Resultado resultado, String idDespacho, String code, String detalle) {}

    /** Cuerpo de 7.1. Código postal y coordenadas son opcionales y M1 no los tiene: no se mandan. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Solicitud(String idPedido, Destinatario destinatario, Destino destino, List<Linea> lineas) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Destinatario(String nombre, String telefono) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Destino(String direccion, String referencia, String departamento, String provincia, String distrito) {}

    public record Linea(String sku, int cantidad) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean simulado;
    private final RestClient http;
    private final TokenServicio tokens;

    public DespachoClient(@Value("${integracion.despacho-simulado:${integracion.simulado:true}}") boolean simulado,
                          @Value("${integracion.despacho-url:http://localhost:8092}") String url,
                          RestClient.Builder builder,
                          TokenServicio tokens) {
        this.simulado = simulado;
        this.http = builder.baseUrl(url).build();
        this.tokens = tokens;
    }

    public Respuesta crear(Solicitud solicitud) {
        if (simulado) {
            log.info("[simulado] despacho de {} -> {}", solicitud.idPedido(), solicitud.destino());
            return new Respuesta(Resultado.SIMULADO, "DESP-SIM-" + solicitud.idPedido(), null, null);
        }
        try {
            ResponseEntity<String> r = http.post().uri("/api/v1/despachos")
                    .headers(tokens.autorizacion())
                    .header("Idempotency-Key", claveIdempotencia(solicitud.idPedido()))
                    .body(solicitud)
                    .retrieve()
                    .toEntity(String.class);
            Resultado resultado = r.getStatusCode().isSameCodeAs(HttpStatus.OK) ? Resultado.YA_EXISTIA : Resultado.CREADO;
            return new Respuesta(resultado, idDespacho(r.getBody()), null, null);
        } catch (HttpStatusCodeException e) {
            tokens.revisar(e);
            String code = code(e.getResponseBodyAsString());
            Resultado resultado = clasificar(e.getStatusCode().value(), code);
            log.warn("Despacho de {}: {} ({} {})", solicitud.idPedido(), resultado, e.getStatusCode().value(), code);
            return new Respuesta(resultado, null, code, e.getStatusCode().value() + (code != null ? " " + code : ""));
        } catch (RestClientException e) {
            log.warn("Despacho de {}: sin respuesta ({})", solicitud.idPedido(), e.getMessage());
            return new Respuesta(Resultado.NO_DISPONIBLE, null, null, e.getMessage());
        }
    }

    static Resultado clasificar(int status, String code) {
        if (status == 422 && SIN_COBERTURA.equals(code)) {
            return Resultado.SIN_COBERTURA;
        }
        if (status >= 500 || status == 401 || status == 403 || status == 408 || status == 429) {
            return Resultado.NO_DISPONIBLE;
        }
        return Resultado.RECHAZADO;
    }

    static String claveIdempotencia(String pedidoId) {
        return UUID.nameUUIDFromBytes(("DESPACHO:" + pedidoId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** El campo exacto del 201 está por confirmar con G2: se acepta idDespacho o id. */
    private static String idDespacho(String cuerpo) {
        JsonNode n = leer(cuerpo);
        for (String campo : List.of("idDespacho", "id")) {
            if (n != null && n.hasNonNull(campo)) {
                return n.get(campo).asText();
            }
        }
        return null;
    }

    private static String code(String cuerpo) {
        JsonNode n = leer(cuerpo);
        return n != null && n.hasNonNull("code") ? n.get("code").asText() : null;
    }

    private static JsonNode leer(String cuerpo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(cuerpo);
        } catch (Exception e) {
            return null;
        }
    }
}

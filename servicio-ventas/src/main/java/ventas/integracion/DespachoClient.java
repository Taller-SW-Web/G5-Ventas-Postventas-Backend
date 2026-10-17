package ventas.integracion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * Con integracion.despacho-simulado=true no llama a nadie.
 * cancelar(): POST /api/v1/despachos/pedidos/{idPedido}/cancelacion, al aprobar la anulación de un pedido en
 * preparación (F2, ESP-06). 409 = el paquete ya salió (acuerdo G2, D8): la anulación ya no procede.
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

    /**
     * CANCELADO: 2xx, Despacho ya no lo entrega. SIN_DESPACHO: 404, el pedido todavía no tenía despacho.
     * EN_CAMINO: 409, el paquete ya salió (rechazo de negocio, no se reintenta). FALLO: otro error o sin respuesta
     * (ESP-06 CA-05). SIMULADO: no se llamó.
     */
    public enum Cancelacion { CANCELADO, SIN_DESPACHO, EN_CAMINO, FALLO, SIMULADO }

    /** Cuerpo de la cancelación: solo el motivo de la anulación (pendiente de confirmar el contrato exacto con G2). */
    private record SolicitudCancelacion(String motivo) {}

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

    public Cancelacion cancelar(String idPedido, String motivo) {
        if (simulado) {
            log.info("[simulado] cancelación del despacho de {} ({})", idPedido, motivo);
            return Cancelacion.SIMULADO;
        }
        try {
            http.post().uri("/api/v1/despachos/pedidos/{idPedido}/cancelacion", idPedido)
                    .headers(tokens.autorizacion())
                    .header("Idempotency-Key", uuidV4("CANCELACION:" + idPedido))
                    .body(new SolicitudCancelacion(motivo))
                    .retrieve()
                    .toBodilessEntity();
            return Cancelacion.CANCELADO;
        } catch (HttpStatusCodeException e) {
            tokens.revisar(e);
            int status = e.getStatusCode().value();
            if (status == 404) {
                return Cancelacion.SIN_DESPACHO;
            }
            if (status == 409) {
                return Cancelacion.EN_CAMINO;
            }
            log.warn("Cancelación del despacho de {}: {} {}", idPedido, status, code(e.getResponseBodyAsString()));
            return Cancelacion.FALLO;
        } catch (RestClientException e) {
            log.warn("Cancelación del despacho de {}: sin respuesta ({})", idPedido, e.getMessage());
            return Cancelacion.FALLO;
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

    /** Misma clave para el mismo pedido (reintentos seguros), con formato UUID v4 (acuerdo G2, D9). */
    static String claveIdempotencia(String pedidoId) {
        return uuidV4("DESPACHO:" + pedidoId);
    }

    /** UUID v4 derivado de un texto: siempre el mismo para el mismo texto. */
    static String uuidV4(String texto) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(texto.getBytes(StandardCharsets.UTF_8));
            h[6] = (byte) ((h[6] & 0x0f) | 0x40);   // versión 4
            h[8] = (byte) ((h[8] & 0x3f) | 0x80);   // variante IETF
            ByteBuffer b = ByteBuffer.wrap(h);
            return new UUID(b.getLong(), b.getLong()).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 siempre existe en la JVM
        }
    }

    /** G2 devuelve el código del despacho en idDespacho (confirmado 16/10). */
    private static String idDespacho(String cuerpo) {
        JsonNode n = leer(cuerpo);
        return n != null && n.hasNonNull("idDespacho") ? n.get("idDespacho").asText() : null;
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

package ventas.integracion;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Productos respondió 429 RATE_LIMIT_EXCEDIDO (contrato H5 D-02 y §6): no es un rechazo de la operación comercial.
 * Quien consulta debe esperar lo que indique Retry-After (null si no vino o no se entiende) y seguir después.
 */
public class LimiteProductosException extends RuntimeException {

    private final transient Duration reintentarEn;

    public LimiteProductosException(Duration reintentarEn) {
        super("Productos respondió 429" + (reintentarEn != null ? " (Retry-After " + reintentarEn.toSeconds() + " s)" : ""));
        this.reintentarEn = reintentarEn;
    }

    public Duration reintentarEn() {
        return reintentarEn;
    }

    /** Retry-After en segundos; la forma de fecha HTTP no se usa en el contrato y se trata como ausente. */
    static LimiteProductosException desde(HttpClientErrorException e) {
        HttpHeaders h = e.getResponseHeaders();
        String valor = h == null ? null : h.getFirst(HttpHeaders.RETRY_AFTER);
        Duration espera = null;
        if (valor != null) {
            try {
                espera = Duration.ofSeconds(Math.max(1, Long.parseLong(valor.trim())));
            } catch (NumberFormatException ignorado) {
                espera = null;
            }
        }
        return new LimiteProductosException(espera);
    }
}

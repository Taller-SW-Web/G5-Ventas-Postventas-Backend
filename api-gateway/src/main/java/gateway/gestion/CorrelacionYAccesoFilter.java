package gateway.gestion;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Trazabilidad de API Management.
 *
 * <ol>
 *   <li>Toma el {@code X-Correlation-Id} del consumidor o genera uno (UUID) si no viene o no es válido.</li>
 *   <li>Lo reenvía a M1/M2 en la misma cabecera y lo devuelve en la respuesta, para seguir una
 *       solicitud de punta a punta (consumidor → gateway → microservicio).</li>
 *   <li>Escribe una línea de log de acceso por solicitud: método, ruta, estado, latencia y correlación.</li>
 * </ol>
 *
 * <p>Es un {@link WebFilter} con la máxima precedencia, así corre antes que Spring Security: también
 * las respuestas 401/403/429 llevan correlación y quedan en el log.
 */
@Component
public class CorrelacionYAccesoFilter implements WebFilter, Ordered {

    public static final String CABECERA = "X-Correlation-Id";
    public static final String ATRIBUTO = CorrelacionYAccesoFilter.class.getName() + ".id";

    private static final Logger LOG = LoggerFactory.getLogger("gateway.acceso");
    /** Letras, dígitos, guion y guion bajo; hasta 64 caracteres (evita inyección en logs y cabeceras). */
    private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String recibido = exchange.getRequest().getHeaders().getFirst(CABECERA);
        String id = (recibido != null && VALIDO.matcher(recibido).matches())
                ? recibido
                : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(CABECERA, id))
                .build();
        ServerWebExchange conId = exchange.mutate().request(request).build();
        conId.getAttributes().put(ATRIBUTO, id);
        conId.getResponse().beforeCommit(() -> {
            conId.getResponse().getHeaders().set(CABECERA, id);
            return Mono.empty();
        });

        long inicio = System.nanoTime();
        return chain.filter(conId).doFinally(senal -> {
            HttpStatusCode estado = conId.getResponse().getStatusCode();
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            LOG.info("{} {} -> {} en {} ms | consumidor={} | correlacion={}",
                    request.getMethod(),
                    request.getURI().getRawPath(),
                    estado != null ? estado.value() : 0,
                    ms,
                    conId.getAttributes().getOrDefault(LimiteSolicitudesFilter.ATRIBUTO_CONSUMIDOR, "-"),
                    id);
        });
    }
}

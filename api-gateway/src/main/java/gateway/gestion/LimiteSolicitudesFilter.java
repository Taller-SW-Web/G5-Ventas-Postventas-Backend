package gateway.gestion;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Rate limiting (cuota por consumidor) del gateway, con el algoritmo token bucket en memoria.
 *
 * <p>Corre como {@link GlobalFilter} (después de Spring Security), así la clave sale del JWT ya
 * verificado y no se puede evadir cambiando el token:
 * <ul>
 *   <li>token técnico ({@code tipo=servicio}) → clave {@code servicio:<client_id>}, cuota {@code servicio}</li>
 *   <li>token de usuario → clave {@code usuario:<sub>}, cuota {@code usuario}</li>
 *   <li>sin token (seguridad deshabilitada) → clave {@code ip:<ip>}, cuota {@code anonimo}</li>
 * </ul>
 *
 * <p>Toda respuesta enrutada lleva {@code X-RateLimit-Limit} y {@code X-RateLimit-Remaining}. Al agotar la
 * cuota responde 429 con {@code Retry-After} (segundos) y un cuerpo JSON con el mismo formato de error
 * de M1/M2, sin llamar al microservicio.
 *
 * <p>Nota: el estado vive en memoria de la instancia (Render ejecuta una sola instancia del gateway).
 * Con varias réplicas se reemplazaría por el RequestRateLimiter de Spring Cloud Gateway sobre Redis.
 */
@Component
public class LimiteSolicitudesFilter implements GlobalFilter, Ordered {

    public static final String ATRIBUTO_CONSUMIDOR = LimiteSolicitudesFilter.class.getName() + ".consumidor";

    private final ApiManagementProperties.RateLimit config;
    private final Clock reloj;
    private final Map<String, Cubeta> cubetas = new ConcurrentHashMap<>();

    @Autowired
    public LimiteSolicitudesFilter(ApiManagementProperties props) {
        this(props, Clock.systemUTC());
    }

    LimiteSolicitudesFilter(ApiManagementProperties props, Clock reloj) {
        this.config = props.rateLimit();
        this.reloj = reloj;
    }

    @Override
    public int getOrder() {
        // Antes del enrutamiento hacia M1/M2
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!config.habilitado()) {
            return chain.filter(exchange);
        }
        return exchange.getPrincipal()
                .map(this::consumidorDesdeToken)
                .defaultIfEmpty(new Consumidor("ip:" + ipCliente(exchange), config.anonimo()))
                .flatMap(c -> aplicar(c, exchange, chain));
    }

    private Mono<Void> aplicar(Consumidor c, ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getAttributes().put(ATRIBUTO_CONSUMIDOR, c.clave());
        purgarSiHaceFalta();

        long ahora = reloj.millis();
        Cubeta cubeta = cubetas.computeIfAbsent(c.clave(), k -> new Cubeta(c.limitePorMinuto(), ahora));
        Resultado r = cubeta.consumir(ahora);

        HttpHeaders cabeceras = exchange.getResponse().getHeaders();
        cabeceras.set("X-RateLimit-Limit", String.valueOf(c.limitePorMinuto()));
        cabeceras.set("X-RateLimit-Remaining", String.valueOf(r.restantes()));

        if (r.permitido()) {
            return chain.filter(exchange);
        }
        return rechazar(exchange, r.segundosParaReintentar());
    }

    private Consumidor consumidorDesdeToken(Principal principal) {
        if (principal instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            if ("servicio".equals(jwt.getClaimAsString("tipo"))) {
                String clientId = jwt.getClaimAsString("client_id");
                return new Consumidor("servicio:" + (clientId != null ? clientId : jwt.getSubject()),
                        config.servicio());
            }
            return new Consumidor("usuario:" + jwt.getSubject(), config.usuario());
        }
        return new Consumidor("usuario:" + principal.getName(), config.usuario());
    }

    /** Render y Cloudflare ponen la IP real del cliente en estas cabeceras. */
    static String ipCliente(ServerWebExchange exchange) {
        HttpHeaders h = exchange.getRequest().getHeaders();
        String cf = h.getFirst("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        String xff = h.getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        var remoto = exchange.getRequest().getRemoteAddress();
        return remoto != null && remoto.getAddress() != null ? remoto.getAddress().getHostAddress() : "desconocida";
    }

    private Mono<Void> rechazar(ServerWebExchange exchange, long segundos) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        resp.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(segundos));
        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Object correlacion = exchange.getAttributes().getOrDefault(CorrelacionYAccesoFilter.ATRIBUTO, "");
        String cuerpo = """
                {"codigo":"LIMITE_SOLICITUDES","mensaje":"Se superó la cuota de solicitudes por minuto. \
                Reintente en %d s.","correlationId":"%s"}""".formatted(segundos, correlacion);
        DataBuffer buffer = resp.bufferFactory().wrap(cuerpo.getBytes(StandardCharsets.UTF_8));
        return resp.writeWith(Mono.just(buffer));
    }

    /** Evita que el mapa crezca sin límite: borra las cubetas llenas (consumidores inactivos ≥ 1 min). */
    private void purgarSiHaceFalta() {
        if (cubetas.size() < config.maxClientes()) {
            return;
        }
        long ahora = reloj.millis();
        cubetas.entrySet().removeIf(e -> e.getValue().inactiva(ahora));
    }

    int clientesEnMemoria() {
        return cubetas.size();
    }

    private record Consumidor(String clave, int limitePorMinuto) { }

    record Resultado(boolean permitido, long restantes, long segundosParaReintentar) { }

    /** Token bucket: capacidad = límite por minuto; se recarga de forma continua (límite / 60 000 ms). */
    static final class Cubeta {
        private final int capacidad;
        private final double recargaPorMs;
        private double fichas;
        private long ultimaRecarga;

        Cubeta(int capacidad, long ahora) {
            this.capacidad = capacidad;
            this.recargaPorMs = capacidad / 60_000.0;
            this.fichas = capacidad;
            this.ultimaRecarga = ahora;
        }

        synchronized Resultado consumir(long ahora) {
            recargar(ahora);
            if (fichas >= 1) {
                fichas -= 1;
                return new Resultado(true, (long) Math.floor(fichas), 0);
            }
            long espera = (long) Math.ceil((1 - fichas) / recargaPorMs / 1000.0);
            return new Resultado(false, 0, Math.max(1, espera));
        }

        synchronized boolean inactiva(long ahora) {
            recargar(ahora);
            return fichas >= capacidad;
        }

        private void recargar(long ahora) {
            long transcurrido = Math.max(0, ahora - ultimaRecarga);
            fichas = Math.min(capacidad, fichas + transcurrido * recargaPorMs);
            ultimaRecarga = ahora;
        }
    }
}

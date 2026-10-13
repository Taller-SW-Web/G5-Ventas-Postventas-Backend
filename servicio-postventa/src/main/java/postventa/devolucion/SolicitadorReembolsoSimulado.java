package postventa.devolucion;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import postventa.compartido.CodigoPublico;

/**
 * Reemplazo temporal de F4: aprueba todo reembolso al instante y lo recuerda en memoria (se pierde al reiniciar).
 * Se borra cuando exista ReembolsoService; ver SolicitadorReembolso.
 */
@Slf4j
@Component
public class SolicitadorReembolsoSimulado implements SolicitadorReembolso {

    private final Map<String, Resultado> porDevolucion = new ConcurrentHashMap<>();
    private final AtomicLong secuencia = new AtomicLong();

    @Override
    public Resultado solicitar(Solicitud s) {
        return porDevolucion.computeIfAbsent(s.devolucionId(), id -> {
            long n = secuencia.incrementAndGet();
            log.info("[simulado] reembolso de {} {} por la devolución {} (clave {})",
                    s.monto(), s.moneda(), id, s.claveIdempotencia());
            return new Resultado(CodigoPublico.reembolso(n), Resultado.EXITOSO, s.monto(), s.moneda(),
                    "TX-SIM-%06d".formatted(n),
                    OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
        });
    }

    @Override
    public Optional<Resultado> consultar(String devolucionId) {
        return Optional.ofNullable(porDevolucion.get(devolucionId));
    }
}

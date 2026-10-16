package pasarela.cobro;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import pasarela.cobro.dto.CobroRequest;
import pasarela.cobro.dto.CobroResponse;
import pasarela.cobro.dto.ConsultaCobroResponse;

/**
 * Registra cobros en memoria y programa su procesamiento.
 *
 * El resultado del cobro es independiente de la entrega de su notificación:
 * si M1 no responde, se conserva el resultado y se reintenta el aviso.
 */
@Service
public class CobroService {

    private static final Logger LOG =
            LoggerFactory.getLogger(CobroService.class);

    private final CobroRepository cobros;
    private final VentasNotificacionClient ventas;
    private final TaskScheduler planificador;
    private final Duration demoraProcesamiento;
    private final Duration demoraReintento;
    private final int reintentos;

    public CobroService(
            CobroRepository cobros,
            VentasNotificacionClient ventas,
            @Qualifier("cobroTaskScheduler")
            TaskScheduler planificador,
            @Value("${pasarela.demora-ms:2000}")
            long demoraMs,
            @Value("${pasarela.reintentos:3}")
            int reintentos,
            @Value("${pasarela.reintento-ms:1000}")
            long reintentoMs) {

        if (demoraMs < 0 || reintentoMs < 0 || reintentos < 0) {
            throw new IllegalArgumentException(
                    "Las demoras y los reintentos no pueden ser negativos");
        }

        this.cobros = Objects.requireNonNull(cobros);
        this.ventas = Objects.requireNonNull(ventas);
        this.planificador = Objects.requireNonNull(planificador);
        this.demoraProcesamiento = Duration.ofMillis(demoraMs);
        this.demoraReintento = Duration.ofMillis(reintentoMs);
        this.reintentos = reintentos;
    }

    /**
     * Recibe una solicitud previamente validada por el controlador.
     * Devuelve el identificador sin esperar el resultado del pago.
     */
    public CobroResponse iniciar(CobroRequest solicitud) {
        Objects.requireNonNull(solicitud);

        String transaccionId = UUID.randomUUID().toString();

        TransaccionCobro transaccion = new TransaccionCobro(
                transaccionId,
                solicitud,
                OffsetDateTime.now(ZoneOffset.UTC));

        cobros.guardar(transaccion);

        planificador.schedule(
                () -> procesar(transaccion),
                Instant.now().plus(demoraProcesamiento));

        return new CobroResponse(
                transaccionId,
                EstadoCobro.PROCESANDO);
    }

    /**
     * Permite consultar tanto el resultado como la entrega del aviso a M1.
     */
    public Optional<ConsultaCobroResponse> consultar(String transaccionId) {
        return cobros.buscar(transaccionId)
                .map(TransaccionCobro::consultar);
    }

    private void procesar(TransaccionCobro transaccion) {
        boolean resuelta = transaccion.resolver(
                OffsetDateTime.now(ZoneOffset.UTC));

        if (resuelta) {
            notificar(transaccion, reintentos);
        }
    }

    /**
     * Realiza un envío inicial y, si falla, hasta el número configurado
     * de reintentos adicionales. Todos utilizan la misma transacción.
     */
    private void notificar(
            TransaccionCobro transaccion,
            int reintentosRestantes) {

        if (transaccion.consultar().notificadoM1()) {
            return;
        }

        transaccion.registrarIntentoNotificacion();

        if (ventas.notificar(transaccion.consultar())) {
            transaccion.marcarNotificado();
            return;
        }

        if (reintentosRestantes == 0) {
            LOG.warn(
                    "Se agotaron los intentos de notificación "
                            + "de la transacción {}",
                    transaccion.getTransaccionId());
            return;
        }

        // Programar otro intento libera el hilo durante la espera.
        planificador.schedule(
                () -> notificar(transaccion, reintentosRestantes - 1),
                Instant.now().plus(demoraReintento));
    }
}

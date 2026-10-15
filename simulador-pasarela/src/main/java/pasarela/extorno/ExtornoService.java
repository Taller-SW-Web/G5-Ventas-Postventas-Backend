package pasarela.extorno;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import pasarela.extorno.dto.ExtornoRequest;
import pasarela.extorno.dto.ExtornoResponse;

/**
 * Simula extornos y conserva sus resultados en memoria.
 * Reiniciar la aplicación elimina los registros.
 */
@Service
public class ExtornoService {

    private record Huella(
            String referencia,
            BigDecimal monto,
            String moneda,
            EscenarioExtorno escenario) {
    }

    private record Registro(
            Huella huella,
            ExtornoResponse respuesta) {
    }

    private final ConcurrentMap<String, Registro> registros =
            new ConcurrentHashMap<>();

    private final long demoraTimeoutMs;

    public ExtornoService(
            @Value("${pasarela.extorno.timeout-ms:10000}")
            long demoraTimeoutMs) {

        if (demoraTimeoutMs < 0) {
            throw new IllegalArgumentException(
                    "La demora del simulador no puede ser negativa");
        }

        this.demoraTimeoutMs = demoraTimeoutMs;
    }

    public ExtornoResponse procesar(
            String clave,
            ExtornoRequest solicitud) {

        if (clave == null || clave.isBlank()) {
            throw new ExtornoException(
                    HttpStatus.BAD_REQUEST,
                    "CLAVE_IDEMPOTENCIA_REQUERIDA",
                    "X-Idempotency-Key es obligatoria");
        }

        Huella huella = validarYCrearHuella(solicitud);
        AtomicBoolean nuevo = new AtomicBoolean(false);

        Registro registro = Objects.requireNonNull(
                registros.compute(clave, (identificador, existente) -> {
                    if (existente != null) {
                        if (!existente.huella().equals(huella)) {
                            throw new ExtornoException(
                                    HttpStatus.CONFLICT,
                                    "CLAVE_IDEMPOTENCIA_EN_CONFLICTO",
                                    "La clave ya fue utilizada con otros datos");
                        }

                        return existente;
                    }

                    nuevo.set(true);

                    return new Registro(
                            huella,
                            crearRespuesta(huella.escenario()));
                }));

        if (nuevo.get() && huella.escenario() == EscenarioExtorno.TIMEOUT) {
            demorarRespuesta();
        }

        return registro.respuesta();
    }

    private Huella validarYCrearHuella(ExtornoRequest solicitud) {
        if (solicitud == null) {
            throw solicitudInvalida();
        }

        String referencia = solicitud.referencia();
        BigDecimal monto = solicitud.monto();
        String moneda = solicitud.moneda();

        if (referencia == null || referencia.isBlank()
                || monto == null || monto.signum() <= 0
                || (!"PEN".equals(moneda) && !"USD".equals(moneda))) {
            throw solicitudInvalida();
        }

        EscenarioExtorno escenario = solicitud.escenario() == null
                ? EscenarioExtorno.EXITOSO
                : solicitud.escenario();

        // 10.0 y 10.00 representan el mismo importe.
        return new Huella(
                referencia,
                monto.stripTrailingZeros(),
                moneda,
                escenario);
    }

    private ExtornoResponse crearRespuesta(EscenarioExtorno escenario) {
        String idTransaccion = "TX-" + UUID.randomUUID();

        if (escenario == EscenarioExtorno.FALLIDO) {
            return new ExtornoResponse(
                    idTransaccion,
                    "FALLIDO",
                    "Extorno rechazado por el simulador");
        }

        return new ExtornoResponse(
                idTransaccion,
                "EXITOSO",
                "Extorno confirmado por el simulador");
    }

    private void demorarRespuesta() {
        try {
            Thread.sleep(demoraTimeoutMs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();

            throw new ExtornoException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SIMULACION_INTERRUMPIDA",
                    "La espera del simulador fue interrumpida");
        }
    }

    private ExtornoException solicitudInvalida() {
        return new ExtornoException(
                HttpStatus.BAD_REQUEST,
                "SOLICITUD_INVALIDA",
                "Referencia, monto positivo y moneda PEN o USD son obligatorios");
    }
}

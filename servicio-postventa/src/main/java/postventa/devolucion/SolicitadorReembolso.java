package postventa.devolucion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Lo que F3 necesita de F4 (ESP-08 flujo D): pedir el reembolso de una devolución aprobada y consultar
 * cómo quedó, para el bloque reembolso de 2.3. F3 nunca ejecuta el extorno.
 *
 * Mientras ReembolsoService (F4, Luis Alejandro) no exista, la implementación es SolicitadorReembolsoSimulado.
 * Cuando exista, se reemplaza por una que delegue en ReembolsoService (mismo microservicio, por su Service,
 * nunca por su repositorio), con origen DEVOLUCION y la clave de idempotencia que llega en la solicitud.
 */
public interface SolicitadorReembolso {

    /** origenId = devolucionId (DEV-AAAA-NNNN), referenciaId = pedidoId, como en api-postventas 2.6. */
    record Solicitud(String claveIdempotencia, String devolucionId, String pedidoId, BigDecimal monto,
                     String moneda, String motivo, UUID autorizadorId) {}

    /** Mismos campos que el bloque resolucion.reembolso de 2.3. estado: PENDIENTE, EXITOSO o FALLIDO. */
    record Resultado(String reembolsoId, String estado, BigDecimal monto, String moneda,
                     String transaccionPasarelaId, OffsetDateTime fechaEjecucion) {

        public static final String EXITOSO = "EXITOSO";

        public boolean exitoso() {
            return EXITOSO.equals(estado);
        }
    }

    /** Idempotente: con la misma clave nunca crea un segundo reembolso, solo reintenta el que existe. */
    Resultado solicitar(Solicitud solicitud);

    Optional<Resultado> consultar(String devolucionId);
}

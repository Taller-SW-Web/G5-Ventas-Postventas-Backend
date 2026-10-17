package pasarela.cobro;

import java.time.OffsetDateTime;
import java.util.Objects;

import pasarela.cobro.dto.CobroRequest;
import pasarela.cobro.dto.ConsultaCobroResponse;

/**
 * Transacción conservada en memoria.
 * Sincroniza los cambios y consultas para devolver un estado consistente.
 */
public class TransaccionCobro {

    private final String transaccionId;
    private final CobroRequest solicitud;
    private final OffsetDateTime fechaCreacion;

    private EstadoCobro estado = EstadoCobro.PROCESANDO;
    private OffsetDateTime fechaPago;
    private boolean notificadoM1;
    private int intentosNotificacion;

    public TransaccionCobro(
            String transaccionId,
            CobroRequest solicitud,
            OffsetDateTime fechaCreacion) {

        this.transaccionId = Objects.requireNonNull(transaccionId);
        this.solicitud = Objects.requireNonNull(solicitud);
        this.fechaCreacion = Objects.requireNonNull(fechaCreacion);
    }

    public String getTransaccionId() {
        return transaccionId;
    }

    /**
     * Resuelve el cobro una sola vez.
     * Devuelve false si otra ejecución ya lo había procesado.
     */
    public synchronized boolean resolver(OffsetDateTime fechaResultado) {
        if (estado != EstadoCobro.PROCESANDO) {
            return false;
        }

        fechaPago = Objects.requireNonNull(fechaResultado);

        estado = solicitud.escenario() == EscenarioCobro.RECHAZADO
                ? EstadoCobro.RECHAZADO
                : EstadoCobro.APROBADO;

        return true;
    }

    /**
     * Registra cada envío HTTP, sin generar otra transacción.
     */
    public synchronized void registrarIntentoNotificacion() {
        exigirCobroProcesado();
        intentosNotificacion++;
    }

    public synchronized void marcarNotificado() {
        exigirCobroProcesado();
        notificadoM1 = true;
    }

    /**
     * Devuelve una copia inmutable del estado actual.
     */
    public synchronized ConsultaCobroResponse consultar() {
        return new ConsultaCobroResponse(
                transaccionId,
                solicitud.pedidoId(),
                solicitud.monto(),
                solicitud.moneda(),
                solicitud.metodoPago(),
                estado,
                notificadoM1,
                intentosNotificacion,
                fechaCreacion,
                fechaPago);
    }

    private void exigirCobroProcesado() {
        if (estado == EstadoCobro.PROCESANDO) {
            throw new IllegalStateException(
                    "El cobro debe procesarse antes de notificar a M1");
        }
    }
}

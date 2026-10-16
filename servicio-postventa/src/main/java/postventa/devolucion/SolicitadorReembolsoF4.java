package postventa.devolucion;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import postventa.compartido.CodigoPublico;
import postventa.reembolso.OrigenReembolso;
import postventa.reembolso.ReembolsoService;
import postventa.reembolso.dto.ProcesarReembolsoRequest;
import postventa.reembolso.dto.ProcesarReembolsoResponse;

/** Adaptador interno: F3 solicita y consulta reembolsos mediante el servicio real de F4. */
@Component
@ConditionalOnProperty(name = "reembolsos.simulado", havingValue = "false", matchIfMissing = true)
public class SolicitadorReembolsoF4 implements SolicitadorReembolso {

    private final ReembolsoService servicio;

    public SolicitadorReembolsoF4(ReembolsoService servicio) {
        this.servicio = servicio;
    }

    @Override
    public Resultado solicitar(Solicitud solicitud) {
        ProcesarReembolsoResponse respuesta = servicio.procesar(solicitud.claveIdempotencia(),
                new ProcesarReembolsoRequest(OrigenReembolso.DEVOLUCION, solicitud.devolucionId(),
                        solicitud.pedidoId(), solicitud.monto(), solicitud.moneda(), solicitud.motivo(),
                        solicitud.autorizadorId() == null ? null : solicitud.autorizadorId().toString()));
        return desde(respuesta);
    }

    @Override
    public Optional<Resultado> consultar(String devolucionId) {
        final long idDevolucion;
        try {
            idDevolucion = CodigoPublico.id(devolucionId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return servicio.consultarPorOrigen(OrigenReembolso.DEVOLUCION, idDevolucion)
                .map(SolicitadorReembolsoF4::desde);
    }

    private static Resultado desde(ProcesarReembolsoResponse respuesta) {
        return new Resultado(respuesta.reembolsoId(), respuesta.estado().name(), respuesta.monto(), respuesta.moneda(),
                respuesta.transaccionPasarelaId(), respuesta.fechaEjecucion());
    }

}

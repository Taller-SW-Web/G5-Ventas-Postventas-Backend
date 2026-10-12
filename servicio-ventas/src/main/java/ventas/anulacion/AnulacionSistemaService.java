package ventas.anulacion;

import java.time.LocalDate;
import java.time.ZoneOffset;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ventas.compartido.CodigoPublico;

/**
 * Registra en ventas.anulacion las anulaciones que dispara un sistema sin pasar por el endpoint 1.7
 * (hoy: Productos rechaza el stock de un pedido ya PAGADO, motivo STOCK_NO_DISPONIBLE).
 * Hace falta porque el reembolso a M2 exige origenId = código de la anulación (ANUL-NNNN).
 * Las anulaciones de F2 (1.7, 1.8, 1.9) las programa Luis Arroyo en su propio service.
 */
@Service
@RequiredArgsConstructor
public class AnulacionSistemaService {

    private final AnulacionRepository anulaciones;

    /** Guarda la anulación ya EJECUTADA y devuelve su código público (ANUL-NNNN). */
    @Transactional(propagation = Propagation.MANDATORY)
    public String registrarEjecutada(Long idPedido, String motivo) {
        Anulacion a = new Anulacion();
        a.setIdPedido(idPedido);
        a.setMotivo(motivo);
        a.setEstado(EstadoAnulacion.EJECUTADA);
        a.setFecha(LocalDate.now(ZoneOffset.UTC));
        anulaciones.saveAndFlush(a);
        return CodigoPublico.anulacion(a.getId());
    }
}

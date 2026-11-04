package ventas.anulacion;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

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

    /**
     * Guarda la anulación ya EJECUTADA y devuelve su código público (ANUL-NNNN).
     * id_pedido es único: si el pedido ya tenía una solicitud (p. ej. RECHAZADA por el Gestor), se reutiliza esa fila.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String registrarEjecutada(Long idPedido, String motivo) {
        Anulacion a = anulaciones.findByIdPedido(idPedido).orElseGet(Anulacion::new);
        a.setIdPedido(idPedido);
        a.setMotivo(motivo);
        a.setEstado(EstadoAnulacion.EJECUTADA);
        a.setFecha(LocalDate.now(ZoneOffset.UTC));
        anulaciones.saveAndFlush(a);
        return CodigoPublico.anulacion(a.getId());
    }

    /**
     * Anulación EJECUTADA de un pedido que ya está ANULADO, para colgarle el reembolso de un cobro que la pasarela
     * confirmó después (1.2). Las anulaciones automáticas desde CREADO (pago rechazado, sin stock, cupón, reserva
     * vencida) no dejan fila en ventas.anulacion. Si ya hay una EJECUTADA se reutiliza sin tocarla; si falta (o la
     * fila es una solicitud que no se ejecutó) se guarda con el motivo y la fecha con que se anuló el pedido, que
     * vienen de su historial. La deja un sistema: sin solicitante ni autorizador.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String asegurarEjecutada(Long idPedido, String motivo, LocalDate fecha) {
        Optional<Anulacion> previa = anulaciones.findByIdPedido(idPedido);
        if (previa.isPresent() && previa.get().getEstado() == EstadoAnulacion.EJECUTADA) {
            return CodigoPublico.anulacion(previa.get().getId());
        }
        Anulacion a = previa.orElseGet(Anulacion::new);
        a.setIdPedido(idPedido);
        a.setMotivo(motivo);
        a.setEstado(EstadoAnulacion.EJECUTADA);
        a.setIdSolicitante(null);
        a.setIdAutorizador(null);
        a.setFecha(fecha);
        anulaciones.saveAndFlush(a);
        return CodigoPublico.anulacion(a.getId());
    }

    /** Código ANUL-NNNN de la anulación EJECUTADA del pedido, si existe (para responder un reintento). */
    @Transactional(readOnly = true)
    public Optional<String> ejecutadaDe(Long idPedido) {
        return anulaciones.findByIdPedido(idPedido)
                .filter(a -> a.getEstado() == EstadoAnulacion.EJECUTADA)
                .map(a -> CodigoPublico.anulacion(a.getId()));
    }
}

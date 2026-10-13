package ventas.pedido;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import ventas.anulacion.AnulacionSistemaService;
import ventas.compartido.CodigoPublico;
import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.PostventaClient;
import ventas.integracion.PostventaClient.ReembolsoRegistrado;
import ventas.integracion.eventos.EventoRecibido;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.dto.EventoLogisticaRequest;
import ventas.pedido.dto.EventoLogisticaResponse;

/**
 * 1.6 Eventos de logística (guía 5.7, ESP-03 7.2): entrega fallida definitiva de un pedido DESPACHADO.
 * En una transacción: registra la anulación EJECUTADA (el reembolso exige su ANUL-NNNN), guarda el evento
 * (idempotencia por pedidoId:guiaRemision:evento) y pasa DESPACHADO -> ANULADO con actor SISTEMA_LOGISTICA.
 * Después del commit pide el reembolso a M2 con origen ANULACION. Un reintento del mismo evento no cambia
 * nada y vuelve a pedir el reembolso con la misma clave, así M2 nunca paga dos veces.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventoLogisticaService {

    static final String ENTREGA_FALLIDA = "ENTREGA_FALLIDA_DEFINITIVA";

    private final PedidoRepository pedidos;
    private final EventoRecibidoRepository recibidos;
    private final MaquinaEstados maquina;
    private final AnulacionSistemaService anulaciones;
    private final PostventaClient postventa;
    private final TransactionOperations transaccion;

    private record Anulado(Pedido pedido, String anulacionId, boolean repetido) {}

    public EventoLogisticaResponse procesar(String pedidoId, EventoLogisticaRequest req) {
        if (!ENTREGA_FALLIDA.equals(req.evento())) {
            throw new SolicitudInvalidaException("EVENTO_NO_SOPORTADO",
                    "Solo se acepta el evento " + ENTREGA_FALLIDA + ", no " + req.evento());
        }
        // Con el pedido en la clave, la misma guía mandada para otro pedido no se toma como reintento
        String idEvento = pedidoId + ":" + req.guiaRemision().trim() + ":" + req.evento();
        Anulado hecho;
        try {
            hecho = transaccion.execute(estado -> aplicar(pedidoId, idEvento));
        } catch (DataIntegrityViolationException e) {
            // El mismo evento llegó dos veces a la vez y el otro ganó
            hecho = transaccion.execute(estado -> repetido(buscar(pedidoId)));
        }
        return responder(hecho, req);
    }

    private Anulado aplicar(String pedidoId, String idEvento) {
        Pedido pedido = buscar(pedidoId);
        if (recibidos.existsByIdEventoAndOrigen(idEvento, OrigenEvento.SISTEMA_LOGISTICA)) {
            return repetido(pedido);
        }
        if (pedido.getEstado() != EstadoPedido.DESPACHADO) {
            throw new ConflictoException("TRANSICION_NO_PERMITIDA",
                    "La entrega fallida solo aplica a pedidos DESPACHADO; %s está en %s".formatted(pedidoId, pedido.getEstado()));
        }
        String anulacionId = anulaciones.registrarEjecutada(pedido.getId(), ENTREGA_FALLIDA);
        recibidos.saveAndFlush(new EventoRecibido(idEvento, OrigenEvento.SISTEMA_LOGISTICA, OffsetDateTime.now(ZoneOffset.UTC)));
        maquina.cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_LOGISTICA, ENTREGA_FALLIDA);
        return new Anulado(pedido, anulacionId, false);
    }

    private Anulado repetido(Pedido pedido) {
        return new Anulado(pedido, anulaciones.ejecutadaDe(pedido.getId()).orElse(null), true);
    }

    private EventoLogisticaResponse responder(Anulado hecho, EventoLogisticaRequest req) {
        Pedido p = hecho.pedido();
        String moneda = p.getMoneda().trim();
        if (!req.conReembolso() || hecho.anulacionId() == null) {
            return new EventoLogisticaResponse(p.getCodigo(), p.getEstado(), hecho.anulacionId(), null,
                    hecho.repetido() ? "Evento ya procesado; sin reembolso" : "Pedido anulado sin reembolso");
        }
        String motivo = "Entrega fallida definitiva (guía %s)%s".formatted(req.guiaRemision().trim(),
                req.motivo() == null || req.motivo().isBlank() ? "" : ": " + req.motivo().trim());
        Optional<ReembolsoRegistrado> r = postventa.solicitarReembolso(hecho.anulacionId(), p.getCodigo(), p.getTotal(),
                moneda, motivo);
        var reembolso = new EventoLogisticaResponse.Reembolso(
                r.map(ReembolsoRegistrado::reembolsoId).orElse(null),
                r.map(ReembolsoRegistrado::estado).orElse("EN_PROCESO"),
                p.getTotal(), moneda);
        String mensaje = hecho.repetido()
                ? "Evento ya procesado; se reenvió la instrucción de reembolso a M2"
                : "Pedido anulado e instrucción de reembolso generada hacia M2 con origen ANULACION";
        return new EventoLogisticaResponse(p.getCodigo(), p.getEstado(), hecho.anulacionId(), reembolso, mensaje);
    }

    private Pedido buscar(String pedidoId) {
        long id = CodigoPublico.id(pedidoId);
        return pedidos.findById(id)
                .filter(p -> p.getCodigo().equals(pedidoId))
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + pedidoId));
    }
}

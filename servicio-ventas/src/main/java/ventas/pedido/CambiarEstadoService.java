package ventas.pedido;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ventas.compartido.CodigoPublico;
import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.pedido.dto.CambiarEstadoRequest;
import ventas.pedido.dto.CambiarEstadoResponse;

/**
 * 1.5 Cambiar estado (guía 5.6): avance logístico PAGADO -> EN_PREPARACION -> DESPACHADO -> ENTREGADO.
 * Solo acepta el actor SISTEMA_LOGISTICA: el pago entra por 1.2 y las anulaciones por F2 o 1.6, nunca por aquí.
 * Si el pedido ya está en el estado pedido, responde 200 sin tocar el historial (reintento de Logística).
 */
@Service
@RequiredArgsConstructor
public class CambiarEstadoService {

    private final PedidoRepository pedidos;
    private final HistorialEstadoRepository historial;
    private final MaquinaEstados maquina;

    @Transactional
    public CambiarEstadoResponse cambiar(String pedidoId, CambiarEstadoRequest req) {
        if (req.nuevoEstado() == EstadoPedido.ANULADO) {
            throw new ConflictoException("TRANSICION_NO_PERMITIDA",
                    "Las anulaciones no usan este endpoint: van por la anulación (F2) o por eventos de logística (1.6)");
        }
        if (req.actor() != ActorHistorial.SISTEMA_LOGISTICA) {
            throw new ConflictoException("TRANSICION_NO_PERMITIDA",
                    "Por este endpoint solo cambia el estado SISTEMA_LOGISTICA, no " + req.actor());
        }
        Pedido pedido = buscar(pedidoId);
        EstadoPedido anterior = pedido.getEstado();
        if (anterior == req.nuevoEstado()) {
            return new CambiarEstadoResponse(pedido.getCodigo(), anterior, anterior, ultimaTransicion(pedido));
        }
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        maquina.cambiar(pedido, req.nuevoEstado(), req.actor(), motivo(req), ahora);
        return new CambiarEstadoResponse(pedido.getCodigo(), anterior, req.nuevoEstado(), ahora);
    }

    private Pedido buscar(String pedidoId) {
        long id = CodigoPublico.id(pedidoId);
        return pedidos.findById(id)
                .filter(p -> p.getCodigo().equals(pedidoId))
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + pedidoId));
    }

    private OffsetDateTime ultimaTransicion(Pedido pedido) {
        var filas = historial.findByPedidoIdOrderByFechaHoraAsc(pedido.getId());
        return filas.isEmpty() ? pedido.getCreadoEn() : filas.get(filas.size() - 1).getFechaHora();
    }

    private static String motivo(CambiarEstadoRequest req) {
        return req.motivo() == null || req.motivo().isBlank() ? null : req.motivo().trim();
    }
}

package ventas.pedido;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ventas.compartido.RecursoNoEncontradoException;
import ventas.pedido.dto.PedidoDetalle;

/**
 * 1.3 Consultar pedido (ESP-02). Devuelve el pedido completo con ítems e historial en orden cronológico.
 * Lo usan el cliente (solo sus pedidos), el Gestor y M2 con token de servicio (F3 y F5 validan que esté ENTREGADO).
 */
@Service
@RequiredArgsConstructor
public class ConsultarPedidoService {

    private final PedidoRepository pedidos;
    private final PagoRepository pagos;
    private final HistorialEstadoRepository historial;

    /**
     * @param soloCliente si viene, el pedido tiene que ser de ese cliente (token de usuario con rol CLIENTE);
     *                    vacío para el Gestor, M2 o con la seguridad apagada.
     */
    @Transactional(readOnly = true)
    public PedidoDetalle consultar(String pedidoId, Optional<UUID> soloCliente) {
        return consultar(pedidoId, soloCliente, false);
    }

    /**
     * @param soloTienda token de servicio de Retail (modulo-retail): solo ve sus pedidos, los de canal RETAIL o con
     *                   retiro en tienda (1.15 y 1.16). Cualquier otro responde 404, como si no existiera, para no
     *                   revelar datos de clientes de otros canales (acuerdo con G7, 2026-11-20).
     */
    @Transactional(readOnly = true)
    public PedidoDetalle consultar(String pedidoId, Optional<UUID> soloCliente, boolean soloTienda) {
        Pedido p = pedidos.findByCodigo(pedidoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO", "No existe el pedido " + pedidoId));
        if (soloCliente.isPresent() && !Objects.equals(soloCliente.get(), p.getIdCliente())) {
            throw new AccessDeniedException("El pedido no pertenece al cliente autenticado");
        }
        if (soloTienda && !esDeTienda(p)) {
            throw new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO", "No existe el pedido " + pedidoId);
        }

        List<PedidoDetalle.Historial> hist = historial.findByPedidoIdOrderByFechaHoraAsc(p.getId()).stream()
                .map(h -> new PedidoDetalle.Historial(h.getEstadoNuevo(), h.getFechaHora(), h.getActor(), h.getMotivo()))
                .toList();
        Optional<Pago> pago = pagos.findByPedidoId(p.getId());

        return new PedidoDetalle(
                p.getCodigo(), p.getCanal(), p.getEstado(), p.getTipo(),
                new PedidoDetalle.Contacto(p.getIdCliente(), p.getNombreContacto(), p.getTipoDocumento(),
                        p.getNumeroDocumento(), p.getEmail(), p.getTelefono()),
                new PedidoDetalle.Pago(p.getTotal(), p.getMoneda().trim(), pago.map(Pago::getMetodo).orElse(null),
                        pago.map(Pago::getEstado).orElse(null)),
                new PedidoDetalle.Envio(p.getModalidadEnvio(), p.getDireccion(), p.getDistrito(), p.getTiendaId(),
                        p.getDepartamento(), p.getProvincia(), p.getReferenciaEnvio(), p.getFechaEstimadaEntrega()),
                p.getDetalles().stream()
                        .map(d -> new PedidoDetalle.Item(d.getIdProducto(), d.getSku(), d.getDescripcion(),
                                d.getCantidad(), d.getPrecioUnitario()))
                        .toList(),
                p.getCreadoEn(),
                hist.isEmpty() ? p.getCreadoEn() : hist.get(hist.size() - 1).fecha(),
                hist);
    }

    /** Venta en tienda o pedido con retiro en tienda: lo que Retail atiende (acuerdo G4, R4 y R12). */
    static boolean esDeTienda(Pedido p) {
        return p.getCanal() == CanalVenta.RETAIL
                || "RECOJO_TIENDA".equals(p.getModalidadEnvio())
                || "TIENDA".equals(p.getModalidadEnvio());
    }
}

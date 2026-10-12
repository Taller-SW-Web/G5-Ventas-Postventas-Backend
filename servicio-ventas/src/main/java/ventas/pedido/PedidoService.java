package ventas.pedido;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ventas.compartido.SolicitudInvalidaException;
import ventas.pedido.dto.MisPedidosResponse;
import ventas.pedido.dto.PedidoResumen;

/** Consultas de pedidos (F1, ESP-02). */
@Service
public class PedidoService {

    static final int TAMANO_MAXIMO = 50;

    private final PedidoRepository pedidos;

    public PedidoService(PedidoRepository pedidos) {
        this.pedidos = pedidos;
    }

    /**
     * 1.4b: pedidos del cliente autenticado. El clienteId sale del claim sub del token,
     * nunca de la URL. page empieza en 1; pageSize entre 1 y 50.
     */
    @Transactional(readOnly = true)
    public MisPedidosResponse listarMios(UUID clienteId, EstadoPedido estado, int page, int pageSize) {
        if (page < 1) {
            throw new SolicitudInvalidaException("PARAMETRO_INVALIDO", "page debe ser 1 o mayor");
        }
        if (pageSize < 1 || pageSize > TAMANO_MAXIMO) {
            throw new SolicitudInvalidaException("PARAMETRO_INVALIDO",
                    "pageSize debe estar entre 1 y " + TAMANO_MAXIMO);
        }
        Pageable pagina = PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Order.desc("creadoEn"), Sort.Order.desc("id")));
        Page<Pedido> resultado = estado == null
                ? pedidos.findByIdCliente(clienteId, pagina)
                : pedidos.findByIdClienteAndEstado(clienteId, estado, pagina);
        return new MisPedidosResponse(
                resultado.getContent().stream().map(PedidoService::resumen).toList(),
                resultado.getTotalElements(), page, pageSize);
    }

    static PedidoResumen resumen(Pedido p) {
        return new PedidoResumen(p.getCodigo(), p.getCanal(), p.getEstado(),
                p.getTotal(), p.getMoneda(), p.getCreadoEn());
    }
}

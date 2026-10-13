package ventas.pedido;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ventas.compartido.ErrorResponse;
import ventas.compartido.SolicitudInvalidaException;
import ventas.pedido.dto.ListadoPedidosResponse;
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
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA", "page debe ser 1 o mayor");
        }
        if (pageSize < 1 || pageSize > TAMANO_MAXIMO) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA",
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

    /**
     * 1.4: listado para el Gestor (rol ADMIN_VENTAS). clienteId obligatorio (api-ventas 1.4); estado, desde y
     * hasta opcionales. pagina empieza en 0; tamano entre 1 y 50. Más recientes primero.
     */
    @Transactional(readOnly = true)
    public ListadoPedidosResponse listar(UUID clienteId, EstadoPedido estado, LocalDate desde, LocalDate hasta,
                                         int pagina, int tamano) {
        if (clienteId == null) {
            throw new SolicitudInvalidaException("PARAMETRO_FALTANTE", "clienteId es obligatorio",
                    List.of(new ErrorResponse.Detalle("clienteId", "es obligatorio")));
        }
        if (pagina < 0) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA", "pagina debe ser 0 o mayor");
        }
        if (tamano < 1 || tamano > TAMANO_MAXIMO) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA", "tamano debe estar entre 1 y " + TAMANO_MAXIMO);
        }
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new SolicitudInvalidaException("PARAMETRO_INVALIDO", "desde no puede ser posterior a hasta");
        }
        Pageable pageable = PageRequest.of(pagina, tamano, Sort.by(Sort.Order.desc("creadoEn"), Sort.Order.desc("id")));
        Page<Pedido> resultado = pedidos.findAll(PedidosFiltro.de(clienteId, estado, desde, hasta), pageable);
        return new ListadoPedidosResponse(clienteId,
                resultado.getContent().stream().map(PedidoService::resumen).toList(),
                pagina, resultado.getTotalPages(), resultado.getTotalElements());
    }

    static PedidoResumen resumen(Pedido p) {
        return new PedidoResumen(p.getCodigo(), p.getCanal(), p.getEstado(),
                p.getTotal(), p.getMoneda(), p.getCreadoEn());
    }
}

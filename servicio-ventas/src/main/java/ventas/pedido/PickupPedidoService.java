package ventas.pedido;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.pedido.dto.EntregaPickupRequest;
import ventas.pedido.dto.EntregaPickupResponse;
import ventas.pedido.dto.PedidoPickupResponse;

@Slf4j
@Service
@RequiredArgsConstructor
public class PickupPedidoService {

    private static final String MOTIVO_ENTREGA_PICKUP = "RETIRO_EN_TIENDA_CONFIRMADO";
    private static final String ESTADO_PICKUP_LISTO = "LISTO_PARA_RECOJO";

    private final PedidoRepository pedidos;
    private final MaquinaEstados maquina;

    /**
     * 1.15 Lista pedidos asignados a una tienda física que se encuentran listos para retiro (Pickup).
     */
    @Transactional(readOnly = true)
    public List<PedidoPickupResponse> listarPendientes(String tiendaId) {
        if (tiendaId == null || tiendaId.isBlank()) {
            throw new SolicitudInvalidaException("PARAMETRO_REQUERIDO", "El parámetro tiendaId es obligatorio");
        }

        List<Pedido> lista = pedidos.buscarParaPickup(
                tiendaId.trim(),
                List.of(EstadoPedido.PAGADO, EstadoPedido.EN_PREPARACION));

        return lista.stream()
                .map(this::mapearPickupResponse)
                .toList();
    }

    /**
     * 1.16 Registra la confirmación de entrega física en mostrador (RF-19).
     */
    @Transactional
    public EntregaPickupResponse entregar(String pedidoId, EntregaPickupRequest solicitud) {
        if (pedidoId == null || pedidoId.isBlank()) {
            throw new SolicitudInvalidaException("PARAMETRO_REQUERIDO", "El identificador del pedido es obligatorio");
        }

        Pedido pedido = buscarPorCodigoOId(pedidoId.trim())
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + pedidoId));

        if (pedido.getEstado() != EstadoPedido.PAGADO && pedido.getEstado() != EstadoPedido.EN_PREPARACION) {
            throw new ConflictoException("PEDIDO_NO_LISTO_PARA_RECOJO",
                    "El paquete no figura en estado listo para recojo (estado actual: " + pedido.getEstado() + ")");
        }

        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        maquina.cambiar(pedido, EstadoPedido.ENTREGADO, ActorHistorial.CLIENTE, MOTIVO_ENTREGA_PICKUP, ahora);

        log.info("Entrega de pedido pickup {} confirmada exitosamente por receptor DNI={}",
                pedido.getCodigo(), solicitud.dniRecoge());

        return new EntregaPickupResponse(
                "CONST-" + pedido.getCodigo(),
                ahora.toString(),
                EstadoPedido.ENTREGADO.name());
    }

    private Optional<Pedido> buscarPorCodigoOId(String identificador) {
        Optional<Pedido> porCodigo = pedidos.bloquearPorCodigo(identificador);
        if (porCodigo.isPresent()) {
            return porCodigo;
        }
        try {
            Long id = Long.parseLong(identificador);
            return pedidos.bloquear(id);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private PedidoPickupResponse mapearPickupResponse(Pedido p) {
        String cliente = (p.getNombreContacto() != null && !p.getNombreContacto().isBlank())
                ? p.getNombreContacto()
                : "Cliente Titular";

        String dni = (p.getNumeroDocumento() != null && !p.getNumeroDocumento().isBlank())
                ? p.getNumeroDocumento()
                : "";

        String anaquel = (p.getReferenciaEnvio() != null && !p.getReferenciaEnvio().isBlank())
                ? p.getReferenciaEnvio()
                : "Trastienda Mostrador";

        String fechaArribo = p.getCreadoEn() != null ? p.getCreadoEn().toString() : "";

        List<PedidoPickupResponse.Articulo> articulos = p.getDetalles().stream()
                .map(d -> new PedidoPickupResponse.Articulo(
                        d.getSku(),
                        d.getDescripcion() != null ? d.getDescripcion() : d.getSku(),
                        "-",
                        "-",
                        d.getCantidad() != null ? d.getCantidad() : 1
                ))
                .toList();

        return new PedidoPickupResponse(
                p.getCodigo(),
                cliente,
                dni,
                anaquel,
                fechaArribo,
                ESTADO_PICKUP_LISTO,
                articulos
        );
    }
}

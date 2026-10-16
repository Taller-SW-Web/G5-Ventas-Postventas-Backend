package ventas.pedido;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import ventas.compartido.CodigoPublico;
import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.MensajeInventario;
import ventas.integracion.ProductosClient;
import ventas.pedido.dto.CrearReemplazoRequest;
import ventas.pedido.dto.CrearReemplazoResponse;

/**
 * 1.11 Orden de reemplazo de un CAMBIO (F3, acuerdo G6 issue #100). La pide M2 al aprobar el cambio.
 * - Es un pedido nuevo (tipo REEMPLAZO), sin cobro (total 0, sin fila de pago), vinculado al pedido original y a la
 *   devolución. Copia el contacto y la dirección del original; Retail no aplica (el cambio se hace en tienda).
 * - Reserva el stock igual que 1.1. Cuando Productos confirma la reserva pasa CREADO -> EN_PREPARACION (no hay pago
 *   que esperar) y el Gestor sigue con "Paquete sellado" (7.1). El consumo se confirma al sellar, no al pagar.
 * - Si la reserva se rechaza o vence, el reemplazo se anula y M2 recibe pedido.anulado con su devolucionId: la
 *   devolución vuelve a evaluación y, al aprobarla otra vez, se crea otro reemplazo con una reserva nueva.
 * - Al entregarse, M2 recibe pedido.entregado con su devolucionId y cierra la devolución.
 * Idempotente por devolución: mientras su reemplazo no esté ANULADO, se devuelve ese mismo (200).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReemplazoService {

    /** creado = true si se registró ahora (201); false si la devolución ya tenía su reemplazo vigente (200). */
    public record Resultado(CrearReemplazoResponse reemplazo, boolean creado) {}

    private record Registro(Pedido pedido, Optional<MensajeInventario> reserva) {}

    private final PedidoRepository pedidos;
    private final MaquinaEstados maquina;
    private final ProductosClient productos;
    private final MensajesProductosService mensajesProductos;
    private final TransactionOperations transaccion;

    public Resultado crear(CrearReemplazoRequest req) {
        Optional<Pedido> vigente = vigente(req.devolucionId());
        if (vigente.isPresent()) {
            return repetido(vigente.get(), req);
        }
        Registro registro;
        try {
            registro = transaccion.execute(estado -> {
                Pedido p = registrar(req);
                // Igual que 1.1: si Productos no admite el comando, el reemplazo no se guarda
                Optional<MensajeInventario> reserva = productos.reservar(p.getCodigo(), p.getCanal().name(),
                        p.getDetalles().stream()
                                .map(d -> new ProductosClient.LineaReserva(d.getSku(), d.getCantidad())).toList());
                return new Registro(p, reserva);
            });
        } catch (DataIntegrityViolationException e) {
            // Otra petición de la misma devolución ganó la carrera (índice único de reemplazo vigente)
            return vigente(req.devolucionId()).map(p -> repetido(p, req)).orElseThrow(() -> e);
        }
        String codigo = registro.pedido().getCodigo();
        // Modo simulado: la reserva creada llega al instante y el reemplazo pasa a EN_PREPARACION
        registro.reserva().ifPresent(m -> {
            try {
                mensajesProductos.procesar(m);
            } catch (RuntimeException e) {
                log.error("No se pudo aplicar la reserva simulada del reemplazo {}", codigo, e);
            }
        });
        Pedido actual = pedidos.findByCodigo(codigo).orElse(registro.pedido());
        return new Resultado(respuesta(actual, req.pedidoOrigenId()), true);
    }

    private Optional<Pedido> vigente(String devolucionId) {
        return pedidos.findFirstByDevolucionIdAndEstadoNotOrderByIdDesc(devolucionId, EstadoPedido.ANULADO);
    }

    private Pedido registrar(CrearReemplazoRequest req) {
        Pedido origen = pedidos.findById(CodigoPublico.id(req.pedidoOrigenId()))
                .filter(p -> p.getCodigo().equals(req.pedidoOrigenId()))
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + req.pedidoOrigenId()));
        if (origen.getEstado() != EstadoPedido.ENTREGADO) {
            throw new ConflictoException("PEDIDO_NO_ENTREGADO",
                    "Solo se repone un pedido ENTREGADO; %s está en %s".formatted(origen.getCodigo(), origen.getEstado()));
        }
        if (origen.getCanal() == CanalVenta.RETAIL) {
            throw new ConflictoException("REEMPLAZO_NO_APLICA", "Los cambios de pedidos RETAIL se hacen en la tienda");
        }
        Map<String, Integer> pedidas = new LinkedHashMap<>();
        req.items().forEach(l -> pedidas.merge(l.sku(), l.cantidad(), Integer::sum));

        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        Pedido p = nuevoPedido(origen, req.devolucionId(), ahora);
        pedidas.forEach((sku, cantidad) -> p.agregarDetalle(linea(origen, sku, cantidad)));
        pedidos.saveAndFlush(p);
        p.setCodigo(CodigoPublico.pedido(p.getId(), ahora.getYear()));
        maquina.registrarInicial(p, ActorHistorial.GESTOR,
                "Reemplazo de %s por la devolución %s".formatted(origen.getCodigo(), req.devolucionId()), ahora);
        return p;
    }

    private static Pedido nuevoPedido(Pedido origen, String devolucionId, OffsetDateTime ahora) {
        Pedido p = new Pedido();
        p.setCodigo(CodigoPublico.temporal());
        p.setTipo(TipoPedido.REEMPLAZO);
        p.setIdPedidoOrigen(origen.getId());
        p.setDevolucionId(devolucionId);
        p.setCanal(origen.getCanal());
        p.setEstado(EstadoPedido.CREADO);
        p.setFecha(ahora.toLocalDate());
        p.setCreadoEn(ahora);
        p.setIdCliente(origen.getIdCliente());
        p.setNombreContacto(origen.getNombreContacto());
        p.setTipoDocumento(origen.getTipoDocumento());
        p.setNumeroDocumento(origen.getNumeroDocumento());
        p.setTelefono(origen.getTelefono());
        p.setEmail(origen.getEmail());
        p.setModalidadEnvio(origen.getModalidadEnvio());
        p.setDestinatario(origen.getDestinatario());
        p.setDistrito(origen.getDistrito());
        p.setDireccion(origen.getDireccion());
        p.setDepartamento(origen.getDepartamento());
        p.setProvincia(origen.getProvincia());
        p.setReferenciaEnvio(origen.getReferenciaEnvio());
        // Sin cobro: el cliente ya pagó el pedido original
        p.setMoneda(origen.getMoneda().trim());
        p.setCostoEnvio(BigDecimal.ZERO);
        p.setDescuento(BigDecimal.ZERO);
        p.setSubtotal(BigDecimal.ZERO);
        p.setTotal(BigDecimal.ZERO);
        return p;
    }

    /** El SKU tiene que estar en el pedido original y no se repone más de lo comprado. */
    private static DetallePedido linea(Pedido origen, String sku, int cantidad) {
        DetallePedido comprado = origen.getDetalles().stream()
                .filter(d -> d.getSku().equals(sku)).findFirst()
                .orElseThrow(() -> new SolicitudInvalidaException("ITEM_NO_PERTENECE_AL_PEDIDO",
                        "El SKU %s no está en el pedido %s".formatted(sku, origen.getCodigo())));
        int compradas = origen.getDetalles().stream()
                .filter(d -> d.getSku().equals(sku)).mapToInt(DetallePedido::getCantidad).sum();
        if (cantidad > compradas) {
            throw new SolicitudInvalidaException("CANTIDAD_EXCEDE_COMPRADA",
                    "Se piden %d de %s y en %s se compraron %d".formatted(cantidad, sku, origen.getCodigo(), compradas));
        }
        DetallePedido d = new DetallePedido();
        d.setIdProducto(comprado.getIdProducto());
        d.setSku(sku);
        d.setDescripcion(comprado.getDescripcion());
        d.setCantidad(cantidad);
        d.setPrecioUnitario(BigDecimal.ZERO);
        d.setDescuento(BigDecimal.ZERO);
        d.setImporte(BigDecimal.ZERO);
        return d;
    }

    private Resultado repetido(Pedido existente, CrearReemplazoRequest req) {
        String origen = existente.getIdPedidoOrigen() == null ? null
                : pedidos.findById(existente.getIdPedidoOrigen()).map(Pedido::getCodigo).orElse(null);
        if (!req.pedidoOrigenId().equals(origen)) {
            throw new ConflictoException("REEMPLAZO_YA_EXISTE",
                    "La devolución %s ya tiene el reemplazo %s de otro pedido".formatted(req.devolucionId(), existente.getCodigo()));
        }
        return new Resultado(respuesta(existente, origen), false);
    }

    private static CrearReemplazoResponse respuesta(Pedido p, String pedidoOrigenId) {
        return new CrearReemplazoResponse(p.getCodigo(), p.getEstado(), p.getDevolucionId(), pedidoOrigenId, p.getCreadoEn());
    }
}

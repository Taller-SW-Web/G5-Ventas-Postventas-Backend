package ventas.pedido;

import java.util.Comparator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import ventas.compartido.CodigoPublico;
import ventas.compartido.ConflictoException;
import ventas.compartido.DependenciaNoDisponibleException;
import ventas.compartido.NoProcesableException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.integracion.DespachoClient;
import ventas.integracion.DespachoClient.Respuesta;
import ventas.integracion.ProductosClient;
import ventas.integracion.ProductosClient.ResultadoConfirmacion;
import ventas.pedido.dto.DespachoPedidoResponse;

/**
 * Los dos botones del Gestor (decisión del equipo 2026-10-13, acuerdo G2 D1):
 * 1. "Iniciar preparación": PAGADO -> EN_PREPARACION. Actor SISTEMA_LOGISTICA, como dice la matriz de ESP-03
 *    para esa transición (es el flujo interno de preparación; el Gestor solo lo dispara).
 * 2. "Paquete sellado": con el pedido EN_PREPARACION, crea el despacho en G2 (POST /api/v1/despachos, 7.1).
 *    No cambia el estado: el pedido pasa a DESPACHADO cuando Despacho avisa que salió.
 *    Orden de reemplazo (F3, acuerdo G6 #100): antes de crear el despacho se consume su reserva en Productos (no
 *    tuvo pago que la consumiera). Si venció, el reemplazo se anula y M2 reabre la devolución.
 * Retail no pasa por aquí: se entrega en tienda en la misma operación de creación.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DespachoPedidoService {

    static final String MOTIVO_PREPARACION = "Preparación iniciada por el Gestor";

    private final PedidoRepository pedidos;
    private final MaquinaEstados maquina;
    private final DespachoClient despacho;
    private final ProductosClient productos;
    private final MensajesProductosService mensajesProductos;
    private final TransactionOperations transaccion;

    /** reservaReemplazo: reservaId que hay que consumir antes de despachar (solo órdenes de reemplazo). */
    private record Preparado(DespachoClient.Solicitud solicitud, String reservaReemplazo) {}

    public DespachoPedidoResponse iniciarPreparacion(String pedidoId) {
        return transaccion.execute(estado -> {
            Pedido p = buscar(pedidoId);
            sinRetail(p);
            if (p.getEstado() == EstadoPedido.EN_PREPARACION) {
                return new DespachoPedidoResponse(p.getCodigo(), p.getEstado(), null, "El pedido ya estaba en preparación");
            }
            if (p.getEstado() != EstadoPedido.PAGADO) {
                throw new ConflictoException("TRANSICION_NO_PERMITIDA",
                        "Solo un pedido PAGADO pasa a preparación; %s está en %s".formatted(pedidoId, p.getEstado()));
            }
            maquina.cambiar(p, EstadoPedido.EN_PREPARACION, ActorHistorial.SISTEMA_LOGISTICA, MOTIVO_PREPARACION);
            return new DespachoPedidoResponse(p.getCodigo(), EstadoPedido.EN_PREPARACION, null, "Pedido en preparación");
        });
    }

    /** La llamada a Despacho va fuera de la transacción: si falla, el pedido se queda EN_PREPARACION. */
    public DespachoPedidoResponse paqueteSellado(String pedidoId) {
        Preparado preparado = transaccion.execute(estado -> {
            Pedido p = buscar(pedidoId);
            return new Preparado(solicitud(p), p.esReemplazo() ? p.getReservaId() : null);
        });
        if (preparado.reservaReemplazo() != null) {
            consumirReservaDelReemplazo(pedidoId, preparado.reservaReemplazo());
        }
        Respuesta r = despacho.crear(preparado.solicitud());
        return switch (r.resultado()) {
            case CREADO, SIMULADO -> listo(pedidoId, r, "Despacho solicitado a G2");
            case YA_EXISTIA -> listo(pedidoId, r, "El pedido ya tenía despacho en G2");
            case SIN_COBERTURA -> throw new NoProcesableException(DespachoClient.SIN_COBERTURA,
                    "Despacho no tiene cobertura para el destino del pedido " + pedidoId);
            case RECHAZADO -> throw new ConflictoException("DESPACHO_RECHAZADO",
                    "Despacho rechazó el pedido %s (%s)".formatted(pedidoId, r.detalle()));
            case NO_DISPONIBLE -> throw new DependenciaNoDisponibleException("DESPACHO_NO_DISPONIBLE",
                    "Despacho no respondió; el pedido sigue EN_PREPARACION, intente nuevamente");
        };
    }

    /** Mismo operation_id en cada intento: si el Gestor vuelve a sellar, Productos no consume dos veces. */
    private void consumirReservaDelReemplazo(String pedidoId, String reservaId) {
        ResultadoConfirmacion c = productos.confirmar(pedidoId, reservaId);
        if (c == ResultadoConfirmacion.RESERVA_EXPIRADA) {
            mensajesProductos.reservaExpiradaReemplazo(pedidoId);
            throw new ConflictoException("RESERVA_EXPIRADA",
                    "La reserva del reemplazo %s venció; se anuló y la devolución vuelve a evaluación".formatted(pedidoId));
        }
        if (c == ResultadoConfirmacion.NO_ENVIADA) {
            throw new DependenciaNoDisponibleException("PRODUCTOS_NO_DISPONIBLE",
                    "Productos no confirmó el consumo del reemplazo; sigue EN_PREPARACION, intente nuevamente");
        }
    }

    private DespachoClient.Solicitud solicitud(Pedido p) {
        sinRetail(p);
        if (p.getEstado() != EstadoPedido.EN_PREPARACION) {
            throw new ConflictoException("ESTADO_NO_PERMITE_DESPACHO",
                    "Solo se despacha un pedido EN_PREPARACION; %s está en %s".formatted(p.getCodigo(), p.getEstado()));
        }
        // G2 exige direccion y distrito; el resto del destino es opcional (acuerdo G2, 16/10)
        if (opcional(p.getDireccion()) == null || opcional(p.getDistrito()) == null) {
            throw new ConflictoException("DATOS_ENVIO_INCOMPLETOS",
                    "El pedido " + p.getCodigo() + " no tiene dirección o distrito de entrega");
        }
        String nombre = p.getDestinatario() != null && !p.getDestinatario().isBlank() ? p.getDestinatario() : p.getNombreContacto();
        return new DespachoClient.Solicitud(
                p.getCodigo(),
                new DespachoClient.Destinatario(nombre, p.getTelefono()),
                new DespachoClient.Destino(p.getDireccion(), opcional(p.getReferenciaEnvio()),
                        opcional(p.getDepartamento()), opcional(p.getProvincia()), p.getDistrito()),
                p.getDetalles().stream()
                        .sorted(Comparator.comparing(DetallePedido::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(d -> new DespachoClient.Linea(d.getSku(), d.getCantidad()))
                        .toList());
    }

    /** G2 prefiere que los campos opcionales vacíos se omitan, no que lleguen como "". */
    private static String opcional(String valor) {
        return valor == null || valor.isBlank() ? null : valor;
    }

    private DespachoPedidoResponse listo(String pedidoId, Respuesta r, String mensaje) {
        log.info("Paquete sellado de {}: {} ({})", pedidoId, r.resultado(), r.idDespacho());
        return new DespachoPedidoResponse(pedidoId, EstadoPedido.EN_PREPARACION, r.idDespacho(), mensaje);
    }

    private static void sinRetail(Pedido p) {
        if (p.getCanal() == CanalVenta.RETAIL) {
            throw new ConflictoException("DESPACHO_NO_APLICA", "Los pedidos RETAIL se entregan en tienda, sin despacho");
        }
    }

    private Pedido buscar(String pedidoId) {
        long id = CodigoPublico.id(pedidoId);
        return pedidos.findById(id)
                .filter(p -> p.getCodigo().equals(pedidoId))
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + pedidoId));
    }
}

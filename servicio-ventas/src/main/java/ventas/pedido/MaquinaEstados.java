package ventas.pedido;

import static ventas.pedido.ActorHistorial.CANAL_CHATBOT;
import static ventas.pedido.ActorHistorial.CLIENTE;
import static ventas.pedido.ActorHistorial.GESTOR;
import static ventas.pedido.ActorHistorial.PASARELA_PAGOS;
import static ventas.pedido.ActorHistorial.SISTEMA_LOGISTICA;
import static ventas.pedido.EstadoPedido.ANULADO;
import static ventas.pedido.EstadoPedido.CREADO;
import static ventas.pedido.EstadoPedido.DESPACHADO;
import static ventas.pedido.EstadoPedido.EN_PREPARACION;
import static ventas.pedido.EstadoPedido.ENTREGADO;
import static ventas.pedido.EstadoPedido.PAGADO;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ventas.compartido.ConflictoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.eventos.EventoPedidoPayload;
import ventas.integracion.eventos.PublicadorEventos;
import ventas.integracion.eventos.TipoEvento;

/** Única forma de cambiar pedido.estado: valida la matriz, guarda el historial y publica el evento. */
@Component
@RequiredArgsConstructor
public class MaquinaEstados {

    private record Transicion(EstadoPedido origen, EstadoPedido destino) {}

    /** Matriz de ESP-03 sección 7.2: transición -> actores permitidos. */
    private static final Map<Transicion, Set<ActorHistorial>> PERMITIDAS = Map.of(
            new Transicion(CREADO, PAGADO),             Set.of(PASARELA_PAGOS),
            new Transicion(CREADO, ANULADO),            Set.of(PASARELA_PAGOS, CANAL_CHATBOT, CLIENTE, GESTOR),
            new Transicion(PAGADO, EN_PREPARACION),     Set.of(SISTEMA_LOGISTICA),
            new Transicion(PAGADO, ANULADO),            Set.of(CLIENTE, GESTOR),
            new Transicion(EN_PREPARACION, DESPACHADO), Set.of(SISTEMA_LOGISTICA),
            new Transicion(EN_PREPARACION, ANULADO),    Set.of(GESTOR),
            new Transicion(DESPACHADO, ENTREGADO),      Set.of(SISTEMA_LOGISTICA),
            new Transicion(DESPACHADO, ANULADO),        Set.of(SISTEMA_LOGISTICA));

    private final PedidoRepository pedidos;
    private final HistorialEstadoRepository historial;
    private final PublicadorEventos eventos;

    @Transactional(propagation = Propagation.MANDATORY)
    public void cambiar(Pedido pedido, EstadoPedido destino, ActorHistorial actor, String motivo) {
        Set<ActorHistorial> actores = PERMITIDAS.get(new Transicion(pedido.getEstado(), destino));
        if (actores == null || !actores.contains(actor)) {
            throw new ConflictoException("TRANSICION_NO_PERMITIDA",
                    "No se permite pasar de %s a %s con actor %s".formatted(pedido.getEstado(), destino, actor));
        }
        if (destino == ANULADO && (motivo == null || motivo.isBlank())) {
            throw new SolicitudInvalidaException("MOTIVO_REQUERIDO", "Toda anulación exige un motivo tipificado");
        }

        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        EstadoPedido anterior = pedido.getEstado();
        pedido.setEstado(destino);
        pedidos.save(pedido);

        HistorialEstado h = new HistorialEstado();
        h.setPedido(pedido);
        h.setEstadoAnterior(anterior);
        h.setEstadoNuevo(destino);
        h.setActor(actor);
        h.setMotivo(motivo);
        h.setFechaHora(ahora);
        historial.save(h);

        TipoEvento tipo = switch (destino) {
            case PAGADO -> TipoEvento.PEDIDO_PAGADO;
            case ANULADO -> TipoEvento.PEDIDO_ANULADO;
            case ENTREGADO -> TipoEvento.PEDIDO_ENTREGADO;
            default -> null;
        };
        if (tipo != null) {
            eventos.publicar(tipo, pedido, payload(pedido, tipo, motivo, ahora));
        }
    }

    private EventoPedidoPayload payload(Pedido pedido, TipoEvento tipo, String motivo, OffsetDateTime fecha) {
        List<EventoPedidoPayload.Item> items = tipo == TipoEvento.PEDIDO_PAGADO
                ? pedido.getDetalles().stream()
                        .map(d -> new EventoPedidoPayload.Item(d.getIdProducto(), d.getCantidad(), d.getImporte()))
                        .toList()
                : null;
        // clienteId es el UUID de Seguridad (claim sub), en texto
        String clienteId = pedido.getIdCliente() == null ? null : pedido.getIdCliente().toString();
        EventoPedidoPayload.Datos datos = new EventoPedidoPayload.Datos(
                pedido.getCanal().name(), clienteId, pedido.getIdVendedor(),
                pedido.getTotal(), pedido.getMoneda(),
                tipo == TipoEvento.PEDIDO_ANULADO ? motivo : null, items);
        return new EventoPedidoPayload(UUID.randomUUID(), tipo.getValor(), pedido.getCodigo(), fecha, datos);
    }
}

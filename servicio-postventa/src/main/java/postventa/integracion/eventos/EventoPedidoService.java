package postventa.integracion.eventos;

import java.util.Objects;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import postventa.dashboard.ProyeccionesService;
import postventa.devolucion.DevolucionService;
import postventa.integracion.ProductosClient;

/**
 * Recibe eventos de M1 y aplica los contadores diarios del dashboard.
 * El registro de idempotencia y las proyecciones comparten la transacción.
 * Los eventos de una orden de reemplazo (datos.devolucionId, F3) no son ventas: no tocan el dashboard y van a la
 * devolución (pedido.entregado la completa, pedido.anulado la devuelve a evaluación).
 */
@Service
public class EventoPedidoService {

    private final ValidadorEventoPedido validador;
    private final EventoRecibidoRepository eventos;
    private final ProyeccionesService proyecciones;
    private final DevolucionService devoluciones;
    private final ProductosClient productos;

    public EventoPedidoService(
            ValidadorEventoPedido validador,
            EventoRecibidoRepository eventos,
            ProyeccionesService proyecciones,
            DevolucionService devoluciones,
            ProductosClient productos) {

        this.validador = Objects.requireNonNull(validador);
        this.eventos = Objects.requireNonNull(eventos);
        this.proyecciones = Objects.requireNonNull(proyecciones);
        this.devoluciones = Objects.requireNonNull(devoluciones);
        this.productos = Objects.requireNonNull(productos);
    }

    /**
     * Devuelve true para un evento nuevo y false para un duplicado.
     * Un fallo al aplicar las proyecciones revierte también el registro,
     * permitiendo que M1 reintente el mismo UUID.
     *
     * No actualiza todavía el agregado de ventas por producto y vendedor,
     * cuyo contrato está pendiente de acuerdo entre M1 y F6.
     */
    @Transactional
    public boolean recibir(EventoPedidoPayload evento) {
        validador.validar(evento);

        if (!eventos.registrarSiNuevo(evento.idEvento(), evento.tipo())) {
            return false;
        }

        String devolucionId = evento.datos().devolucionId();
        if (devolucionId != null) {
            switch (evento.tipo()) {
                case "pedido.entregado" -> devoluciones.reemplazoEntregado(devolucionId, evento.pedidoId());
                case "pedido.anulado" -> devoluciones.reemplazoAnulado(devolucionId, evento.pedidoId(),
                        evento.datos().motivo());
                default -> { }   // un reemplazo no se cobra: no hay pedido.pagado
            }
            return true;
        }

        // Cambio de prenda en tienda (acuerdo G4 + G6, 26/11): Ventas consume la prenda nueva y Postventa reintegra
        // la devuelta. Si Productos no responde, se lanza 503 y se revierte el registro: M1 reintenta el mismo evento
        // y el operation_id fijo evita reintegrar dos veces.
        EventoPedidoPayload.CambioTienda cambio = evento.datos().cambioTienda();
        if (cambio != null && "pedido.entregado".equals(evento.tipo())) {
            productos.reintegrar(operacionCambioTienda(evento.pedidoId()), cambio.pedidoOrigenId(), evento.pedidoId(),
                    cambio.itemsDevueltos().stream()
                            .map(i -> new ProductosClient.LineaReintegro(i.sku(), i.cantidad())).toList());
        }

        // Se conserva la fecha del hecho; F6 determina su día en UTC.
        proyecciones.aplicarEventoPedido(
                evento.tipo(),
                evento.fecha(),
                evento.datos().canal());

        return true;
    }

    /** Siempre el mismo operation_id para el reintegro del cambio de un mismo pedido nuevo. */
    static UUID operacionCambioTienda(String pedidoId) {
        return UUID.nameUUIDFromBytes(("REINTEGRO_CAMBIO_TIENDA:" + pedidoId).getBytes(StandardCharsets.UTF_8));
    }
}

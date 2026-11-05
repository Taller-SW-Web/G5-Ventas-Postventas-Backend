package postventa.integracion.eventos;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import postventa.dashboard.ProyeccionesService;
import postventa.dashboard.ProyeccionVentasService;
import postventa.dashboard.ProyeccionVentasService.LineaVenta;
import postventa.devolucion.DevolucionService;

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
    private final ProyeccionVentasService ventas;

    public EventoPedidoService(
            ValidadorEventoPedido validador,
            EventoRecibidoRepository eventos,
            ProyeccionesService proyecciones,
            DevolucionService devoluciones,
            ProyeccionVentasService ventas) {

        this.validador = Objects.requireNonNull(validador);
        this.eventos = Objects.requireNonNull(eventos);
        this.proyecciones = Objects.requireNonNull(proyecciones);
        this.devoluciones = Objects.requireNonNull(devoluciones);
        this.ventas = Objects.requireNonNull(ventas);
    }

    /**
     * Devuelve true para un evento nuevo y false para un duplicado.
     * Un fallo al aplicar las proyecciones revierte también el registro,
     * permitiendo que M1 reintente el mismo UUID.
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

        // Se conserva la fecha del hecho; F6 determina su día en UTC.
        proyecciones.aplicarEventoPedido(
                evento.tipo(),
                evento.fecha(),
                evento.datos().canal());

        if ("pedido.pagado".equals(evento.tipo())) {
            // El UUID ya fue registrado: un reintento no vuelve a sumar estas líneas.
            ventas.pedidoPagado(evento.fecha(), evento.datos().canal(), evento.datos().vendedorId(),
                    evento.datos().items().stream()
                            .map(item -> new LineaVenta(item.productoId(), item.cantidad(), item.importe()))
                            .toList());
        }

        return true;
    }
}
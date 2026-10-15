package postventa.integracion.eventos;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import postventa.dashboard.ProyeccionesService;

/**
 * Recibe eventos de M1 y aplica los contadores diarios del dashboard.
 * El registro de idempotencia y las proyecciones comparten la transacción.
 */
@Service
public class EventoPedidoService {

    private final ValidadorEventoPedido validador;
    private final EventoRecibidoRepository eventos;
    private final ProyeccionesService proyecciones;

    public EventoPedidoService(
            ValidadorEventoPedido validador,
            EventoRecibidoRepository eventos,
            ProyeccionesService proyecciones) {

        this.validador = Objects.requireNonNull(validador);
        this.eventos = Objects.requireNonNull(eventos);
        this.proyecciones = Objects.requireNonNull(proyecciones);
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

        // Se conserva la fecha del hecho; F6 determina su día en UTC.
        proyecciones.aplicarEventoPedido(
                evento.tipo(),
                evento.fecha(),
                evento.datos().canal());

        return true;
    }
}
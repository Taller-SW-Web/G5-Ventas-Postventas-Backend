package ventas.pedido.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import ventas.pedido.ActorHistorial;
import ventas.pedido.EstadoPedido;

/** Cuerpo de 1.5 Cambiar estado (api-ventas.md 1.5). Lo envía Despacho / Logística. */
public record CambiarEstadoRequest(
        @NotNull EstadoPedido nuevoEstado,
        @NotNull ActorHistorial actor,
        @Size(max = 60) String motivo) {}   // historial_estado.motivo es varchar(60)

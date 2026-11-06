package ventas.pedido.dto;

/**
 * Constancia digital emitida tras la entrega física en tienda (RF-19).
 */
public record EntregaPickupResponse(
        String constanciaId,
        String fechaHoraEntrega,
        String estado) {}

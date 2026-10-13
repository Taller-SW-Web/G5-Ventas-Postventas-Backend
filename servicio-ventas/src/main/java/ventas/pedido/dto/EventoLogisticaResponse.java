package ventas.pedido.dto;

import java.math.BigDecimal;

import ventas.pedido.EstadoPedido;

/** Respuesta 200 de 1.6. reembolso es null si Logística mandó requiereReembolso=false. */
public record EventoLogisticaResponse(
        String pedidoId,
        EstadoPedido estadoPedido,
        String anulacionId,
        Reembolso reembolso,
        String mensaje) {

    /** solicitudId es el REEM-NNNNN de M2; null si M2 todavía no respondió (modo simulado o caída). */
    public record Reembolso(String solicitudId, String estado, BigDecimal monto, String moneda) {}
}

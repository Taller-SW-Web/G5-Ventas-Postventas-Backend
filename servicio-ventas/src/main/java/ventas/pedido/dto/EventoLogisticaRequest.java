package ventas.pedido.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de 1.6 Eventos de logística (api-ventas.md 1.6). Lo envía Despacho / Logística (módulo E). */
public record EventoLogisticaRequest(
        @NotBlank @Size(max = 40) String evento,         // hoy solo ENTREGA_FALLIDA_DEFINITIVA
        @NotBlank @Size(max = 50) String guiaRemision,   // pedidoId:guiaRemision:evento es la clave de idempotencia
        @Size(max = 255) String motivo,                  // texto libre de Logística; va al reembolso
        Boolean requiereReembolso) {                     // null = true: el pedido despachado ya estaba cobrado

    public boolean conReembolso() {
        return requiereReembolso == null || requiereReembolso;
    }
}

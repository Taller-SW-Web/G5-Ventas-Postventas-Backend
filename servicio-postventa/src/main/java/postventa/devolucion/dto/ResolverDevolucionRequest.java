package postventa.devolucion.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import postventa.devolucion.DecisionDevolucion;
import postventa.devolucion.TipoResolucion;

/**
 * Cuerpo de 2.5. fundamento es obligatorio al rechazar.
 * tipoResolucion es opcional: por defecto el camino sale del tipo del expediente (CAMBIO -> CAMBIO,
 * DEVOLUCION_DINERO -> REEMBOLSO). Sirve para resolver como REEMBOLSO un cambio sin stock (ESP-08 CA-05).
 * autorizadorId solo se usa con la seguridad apagada; con token, el autorizador es el sub del Gestor.
 * requiereDevolucionFisica es opcional y solo aplica a un REEMBOLSO (por defecto true, ESP-08 regla 16): con false
 * no se espera la recepción física (2.5b) ni se reintegra stock. En CAMBIO siempre es true.
 */
public record ResolverDevolucionRequest(
        @NotNull DecisionDevolucion decision,
        @Size(max = 2000) String fundamento,
        String autorizadorId,
        TipoResolucion tipoResolucion,
        Boolean requiereDevolucionFisica) {

    /** Sin requiereDevolucionFisica: se toma true. */
    public ResolverDevolucionRequest(DecisionDevolucion decision, String fundamento, String autorizadorId,
                                     TipoResolucion tipoResolucion) {
        this(decision, fundamento, autorizadorId, tipoResolucion, null);
    }
}

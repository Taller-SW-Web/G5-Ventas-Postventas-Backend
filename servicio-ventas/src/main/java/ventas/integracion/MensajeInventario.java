package ventas.integracion;

import java.util.Map;

/**
 * Sobre de un mensaje de Productos (G6) en po.sales.q (asyncapi.yaml, MessageEnvelope).
 * El data es genérico en el contrato: los nombres de sus campos están en constantes para cambiarlos en un solo lugar
 * si Productos los confirma distinto (pregunta 1 a Productos).
 */
public record MensajeInventario(String messageId, String name, String operationId, Map<String, Object> data) {

    public static final String RESERVA_CREADA = "inventory.reservation.created";
    public static final String RESERVA_CONSUMIDA = "inventory.reservation.consumed";
    public static final String RESERVA_LIBERADA = "inventory.reservation.released";
    public static final String RESERVA_EXPIRADA = "inventory.reservation.expired";
    public static final String CONSUMO_COMPLETADO = "inventory.consumption.completed";
    public static final String CONSUMO_RECHAZADO = "inventory.consumption.rejected";

    /** Supuestos de SPEC-015 §30, sin confirmar en el asyncapi (data es GenericData). */
    static final String CAMPO_PEDIDO = "order_id";
    static final String CAMPO_RESERVA = "reservation_id";
    static final String CAMPO_CODIGO = "code";

    public MensajeInventario {
        data = data == null ? Map.of() : data;
    }

    /** Código del pedido (PED-...) que mandamos como order_id. */
    public String pedidoId() {
        return texto(CAMPO_PEDIDO);
    }

    public String reservaId() {
        return texto(CAMPO_RESERVA);
    }

    /** Código del rechazo (STOCK_INSUFICIENTE, RESERVA_EXPIRADA...), solo informativo. */
    public String codigo() {
        return texto(CAMPO_CODIGO);
    }

    private String texto(String campo) {
        Object v = data.get(campo);
        return v == null ? null : v.toString();
    }
}

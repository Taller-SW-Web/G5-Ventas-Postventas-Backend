package postventa.devolucion.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import postventa.devolucion.ActorDevolucion;
import postventa.devolucion.DecisionDevolucion;
import postventa.devolucion.EstadoDevolucion;
import postventa.devolucion.EstadoReintegro;
import postventa.devolucion.MotivoDevolucion;
import postventa.devolucion.TipoDevolucion;
import postventa.devolucion.TipoEvidencia;
import postventa.devolucion.TipoResolucion;

/** Respuesta de 2.3 (contrato v1.5.0). resolucion es null mientras no hay dictamen. */
public record DevolucionDetalle(String devolucionId, String pedidoId, UUID clienteId, TipoDevolucion tipo,
                                EstadoDevolucion estado, MotivoDevolucion motivo, String descripcion,
                                List<Item> items, Resolucion resolucion, List<Evidencia> evidencias,
                                OffsetDateTime fechaRegistro, List<Historial> historial, List<Recepcion> recepciones) {

    public record Item(String productoId, int cantidad) {}

    public record Evidencia(TipoEvidencia tipo, String url) {}

    /** Un cambio de estado (RNF-03). estadoAnterior es null en el registro inicial. */
    public record Historial(EstadoDevolucion estadoAnterior, EstadoDevolucion estado, ActorDevolucion actor,
                            String idActor, String detalle, OffsetDateTime fecha) {}

    /**
     * referenciaOperacion es el reembolsoId de F4 o la referencia de Despacho; null en un rechazo.
     * requiereDevolucionFisica: si se espera la recepción física (2.5b) antes de completar.
     */
    public record Resolucion(DecisionDevolucion decision, TipoResolucion tipoResolucion, String fundamento,
                             UUID autorizadorId, OffsetDateTime fechaResolucion, String referenciaOperacion,
                             boolean requiereDevolucionFisica, Reembolso reembolso) {}

    /** Una línea recibida en almacén (2.5b). reintegroEstado PENDIENTE si Productos todavía no lo aplicó. */
    public record Recepcion(String sku, String productoId, int cantidadRecibida, int cantidadApta,
                            EstadoReintegro reintegroEstado, String idActor, OffsetDateTime fecha) {}

    public record Reembolso(String reembolsoId, String estado, BigDecimal monto, String moneda,
                            String transaccionPasarelaId, OffsetDateTime fechaEjecucion) {}
}

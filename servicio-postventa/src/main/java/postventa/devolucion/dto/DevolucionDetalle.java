package postventa.devolucion.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import postventa.devolucion.DecisionDevolucion;
import postventa.devolucion.EstadoDevolucion;
import postventa.devolucion.MotivoDevolucion;
import postventa.devolucion.TipoDevolucion;
import postventa.devolucion.TipoEvidencia;
import postventa.devolucion.TipoResolucion;

/**
 * Respuesta de 2.3. resolucion es null mientras no hay dictamen. Agrega items y resolucion.tipoResolucion
 * (CAMBIO, REEMBOLSO o RECHAZO) al ejemplo del contrato, para que el expediente muestre el camino tomado.
 */
public record DevolucionDetalle(String devolucionId, String pedidoId, UUID clienteId, TipoDevolucion tipo,
                                EstadoDevolucion estado, MotivoDevolucion motivo, String descripcion,
                                List<Item> items, Resolucion resolucion, List<Evidencia> evidencias,
                                OffsetDateTime fechaRegistro) {

    public record Item(String productoId, int cantidad) {}

    public record Evidencia(TipoEvidencia tipo, String url) {}

    /** fechaResolucion queda null: la tabla devolucion todavía no la guarda. */
    public record Resolucion(DecisionDevolucion decision, TipoResolucion tipoResolucion, String fundamento,
                             UUID autorizadorId, OffsetDateTime fechaResolucion, Reembolso reembolso) {}

    public record Reembolso(String reembolsoId, String estado, BigDecimal monto, String moneda,
                            String transaccionPasarelaId, OffsetDateTime fechaEjecucion) {}
}

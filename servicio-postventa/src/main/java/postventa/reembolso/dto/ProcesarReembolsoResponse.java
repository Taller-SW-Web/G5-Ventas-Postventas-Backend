package postventa.reembolso.dto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import postventa.reembolso.EstadoReembolso;
public record ProcesarReembolsoResponse(String reembolsoId, String transaccionPasarelaId, EstadoReembolso estado,
                                         BigDecimal monto, String moneda, OffsetDateTime fechaEjecucion) {}

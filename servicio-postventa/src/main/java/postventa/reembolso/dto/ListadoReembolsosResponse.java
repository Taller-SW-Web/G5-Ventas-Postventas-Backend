package postventa.reembolso.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import postventa.reembolso.EstadoReembolso;
import postventa.reembolso.OrigenReembolso;

/**
 * Bandeja de reembolsos del Gestor (mockup 08). No está en el contrato: lo agrega M2 para que el portal
 * liste los reembolsos que hoy solo se crean por POST. ultimoResultado e intentos alimentan el overlay
 * "Resultado reembolso".
 */
public record ListadoReembolsosResponse(List<Resumen> contenido, int pagina, int totalPaginas, long totalElementos) {

    public record Resumen(String reembolsoId, String pedidoId, OrigenReembolso origen, String origenId,
                          BigDecimal monto, String moneda, EstadoReembolso estado, String transaccionPasarelaId,
                          LocalDate fecha, int intentos, String ultimoResultado, OffsetDateTime fechaUltimoIntento) {}
}

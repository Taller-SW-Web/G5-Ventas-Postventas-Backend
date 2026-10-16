package postventa.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Respuesta de 2.12. ultimaActualizacion va además del contrato: dice hasta cuándo están consolidados los
 * agregados (ESP-15 §7); es null si el periodo no tiene actividad. csatPromedio es null si no hay encuestas.
 */
public record MetricasResponse(Periodo periodo, Pedidos pedidos, Postventa postventa,
                               OffsetDateTime ultimaActualizacion) {

    public record Periodo(LocalDate desde, LocalDate hasta) {}

    public record Pedidos(long total, long anulados, long entregados) {}

    public record Postventa(long devolucionesAprobadas, BigDecimal montoReembolsadoPEN, BigDecimal csatPromedio,
                            long reclamosPendientesSLA) {}
}

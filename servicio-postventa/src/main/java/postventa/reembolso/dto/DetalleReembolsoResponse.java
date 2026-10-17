package postventa.reembolso.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import postventa.compartido.CanalVenta;
import postventa.reembolso.EstadoReembolso;
import postventa.reembolso.OrigenReembolso;

/** Datos del panel "Ver" del mockup 08. Cliente y canal se obtienen de M1 solo para esta consulta puntual. */
public record DetalleReembolsoResponse(String reembolsoId, String pedidoId, OrigenReembolso origen, String origenId,
        BigDecimal monto, String moneda, EstadoReembolso estado, String transaccionPasarelaId, LocalDate fecha,
        UUID autorizadorId, UUID clienteId, String clienteNombre, CanalVenta canal, String metodoPago,
        boolean total, String claveIdempotencia, List<Intento> intentos) {

    public record Intento(int numero, EstadoReembolso estado, String transaccionPasarelaId, String resultado,
                          OffsetDateTime fecha) {}
}

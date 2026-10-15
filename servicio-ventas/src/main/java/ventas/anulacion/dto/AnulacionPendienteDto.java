package ventas.anulacion.dto;

import java.time.LocalDate;

/** Fila de la bandeja 1.8 GET /api/v1/pedidos/anulaciones/pendientes */
public record AnulacionPendienteDto(
        String solicitudAnulacionId,
        String pedidoId,
        String motivo,
        LocalDate fechaSolicitud
) {
}

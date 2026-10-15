package ventas.anulacion.dto;

import java.util.List;

/** Contenedor paginado para 1.8 GET /api/v1/pedidos/anulaciones/pendientes */
public record ListadoAnulacionesPendientesResponse(
        List<AnulacionPendienteDto> contenido,
        int pagina,
        int totalPaginas,
        long totalElementos
) {
}

package ventas.anulacion.dto;

import java.time.LocalDate;
import ventas.anulacion.EstadoAnulacion;
import ventas.pedido.CanalVenta;
import ventas.pedido.EstadoPedido;

/** Fila de la bandeja 1.8 GET /api/v1/pedidos/anulaciones */
public record AnulacionPendienteDto(
        String solicitudAnulacionId,
        String pedidoId,
        String motivo,
        LocalDate fechaSolicitud,
        EstadoAnulacion estado,
        CanalVenta canal,
        String nombreCliente,
        EstadoPedido estadoPedido
) {
}

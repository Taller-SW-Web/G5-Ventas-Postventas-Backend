package ventas.pedido.dto;

import java.util.List;
import java.util.UUID;

/** Respuesta de 1.4 Listar pedidos (uso administrativo). pagina empieza en 0. */
public record ListadoPedidosResponse(
        UUID clienteId,
        List<PedidoResumen> contenido,
        int pagina,
        int totalPaginas,
        long totalElementos) {}

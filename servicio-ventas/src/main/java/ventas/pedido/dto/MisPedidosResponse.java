package ventas.pedido.dto;

import java.util.List;

/**
 * Respuesta de GET /api/v1/pedidos/me (1.4b).
 * Usa items/total/page/pageSize por acuerdo H-03 con Marketplace: es una excepción deliberada
 * al formato contenido/totalElementos del resto de la API.
 */
public record MisPedidosResponse(List<PedidoResumen> items, long total, int page, int pageSize) {}

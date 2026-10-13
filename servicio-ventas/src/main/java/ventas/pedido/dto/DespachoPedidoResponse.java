package ventas.pedido.dto;

import ventas.pedido.EstadoPedido;

/** Respuesta de los botones del Gestor "Iniciar preparación" y "Paquete sellado". idDespacho solo en el segundo. */
public record DespachoPedidoResponse(String pedidoId, EstadoPedido estado, String idDespacho, String mensaje) {}

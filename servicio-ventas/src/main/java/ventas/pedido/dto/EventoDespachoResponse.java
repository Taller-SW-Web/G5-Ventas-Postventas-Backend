package ventas.pedido.dto;

import ventas.pedido.EstadoPedido;

/** Respuesta 200 del webhook. repetido=true: ese idEvento ya se había procesado y no se cambió nada. */
public record EventoDespachoResponse(String idEvento, String pedidoId, EstadoPedido estadoPedido, boolean repetido,
                                     String mensaje) {}

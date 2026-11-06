package ventas.pedido.dto;

import java.util.List;

/**
 * Respuesta para la bandeja de pedidos para retiro en tienda física (F1 / RF-18 de Retail).
 * Compatible con la estructura esperada por PickupService de Retail.
 */
public record PedidoPickupResponse(
        String pedidoId,
        String clienteNombre,
        String clienteDni,
        String anaquelUbicacion,
        String fechaArriboTienda,
        String estado,
        List<Articulo> articulos) {

    public record Articulo(String sku, String nombre, String talla, String color, int cantidad) {}
}

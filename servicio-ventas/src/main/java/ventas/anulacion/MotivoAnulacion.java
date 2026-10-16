package ventas.anulacion;

import java.util.Set;
import ventas.pedido.EstadoPedido;

/**
 * Catálogo cerrado de motivos de anulación (ESP-05 sección 3.2).
 */
public enum MotivoAnulacion {
    PAGO_NO_COMPLETADO(Set.of(EstadoPedido.CREADO)),
    ERROR_SELECCION_PRODUCTO(Set.of(EstadoPedido.CREADO, EstadoPedido.PAGADO)),
    ARREPENTIMIENTO_COMPRA(Set.of(EstadoPedido.CREADO, EstadoPedido.PAGADO)),
    SOLICITUD_CLIENTE(Set.of(EstadoPedido.CREADO, EstadoPedido.PAGADO, EstadoPedido.EN_PREPARACION)),
    ERROR_DATOS_ENVIO(Set.of(EstadoPedido.CREADO, EstadoPedido.PAGADO, EstadoPedido.EN_PREPARACION)),
    DUPLICADO(Set.of(EstadoPedido.CREADO, EstadoPedido.PAGADO));

    private final Set<EstadoPedido> estadosPermitidos;

    MotivoAnulacion(Set<EstadoPedido> estadosPermitidos) {
        this.estadosPermitidos = estadosPermitidos;
    }

    public boolean aplicaA(EstadoPedido estado) {
        return estadosPermitidos.contains(estado);
    }
}

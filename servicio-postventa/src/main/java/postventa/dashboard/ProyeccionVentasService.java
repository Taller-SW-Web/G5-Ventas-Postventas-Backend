package postventa.dashboard;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import postventa.compartido.CanalVenta;

/**
 * F6 (ESP-15): ventas por producto y vendedor en agregado_ventas, a partir de pedido.pagado (guía 10.4). Lo llama
 * el receptor de eventos (2.13) en la misma transacción que registra el idEvento, igual que ProyeccionesService.
 *
 * id_vendedor es NOT NULL y parte de la clave: un pedido sin vendedor (hoy, todos los que no son Retail) se guarda
 * con SIN_VENDEDOR, así las ventas por producto y canal se cuentan igual.
 */
@Slf4j
@Service
public class ProyeccionVentasService {

    /** Vendedor de las ventas que no tienen uno (Marketplace, Chatbot o Retail sin vendedor informado). */
    public static final UUID SIN_VENDEDOR = new UUID(0L, 0L);

    private static final DateTimeFormatter PERIODO = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int LARGO_MAXIMO_PRODUCTO = 64;

    /** Una línea del pedido pagado: productoId de Productos (G6), tal como lo manda M1. */
    public record LineaVenta(String productoId, int cantidad, BigDecimal importe) {}

    private final AgregadoVentasRepository ventas;

    public ProyeccionVentasService(AgregadoVentasRepository ventas) {
        this.ventas = ventas;
    }

    /** Periodo AAAA-MM de la fecha UTC del pago. Una línea inválida se omite con un aviso; nunca revierte el evento. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void pedidoPagado(OffsetDateTime fecha, CanalVenta canal, UUID vendedorId, List<LineaVenta> lineas) {
        if (canal == null || lineas == null || lineas.isEmpty()) {
            log.warn("pedido.pagado sin canal o sin ítems: no suma a agregado_ventas");
            return;
        }
        String periodo = fecha.withOffsetSameInstant(ZoneOffset.UTC).format(PERIODO);
        UUID vendedor = vendedorId == null ? SIN_VENDEDOR : vendedorId;
        for (LineaVenta linea : lineas) {
            if (!valida(linea)) {
                log.warn("Línea {} inválida: no suma a agregado_ventas", linea);
                continue;
            }
            ventas.sumar(periodo, canal, vendedor, linea.productoId().trim(), linea.cantidad(), linea.importe());
        }
    }

    private static boolean valida(LineaVenta l) {
        return l != null && l.productoId() != null && !l.productoId().isBlank()
                && l.productoId().trim().length() <= LARGO_MAXIMO_PRODUCTO
                && l.cantidad() > 0 && l.importe() != null && l.importe().signum() >= 0;
    }
}

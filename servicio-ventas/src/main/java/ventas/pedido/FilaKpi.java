package ventas.pedido;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Proyección de pedido con solo las columnas que necesita 1.14 (no carga ítems ni contacto). */
public interface FilaKpi {

    OffsetDateTime getCreadoEn();

    CanalVenta getCanal();

    EstadoPedido getEstado();

    BigDecimal getTotal();
}

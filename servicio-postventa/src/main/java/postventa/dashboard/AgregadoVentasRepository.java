package postventa.dashboard;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import postventa.compartido.CanalVenta;

/**
 * Tabla postventa.agregado_ventas: una fila por (periodo AAAA-MM, canal, vendedor, producto). Se suma con upsert
 * nativo, igual que agregado_metricas, para que dos eventos del mismo producto no se pisen (guía 10.4).
 */
@Repository
public class AgregadoVentasRepository {

    private final JdbcTemplate jdbc;

    public AgregadoVentasRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sumar(String periodo, CanalVenta canal, UUID vendedor, String producto, int unidades,
                      BigDecimal monto) {
        jdbc.update("""
                INSERT INTO postventa.agregado_ventas
                    (periodo, canal, id_vendedor, id_producto, unidades, monto, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (periodo, canal, id_vendedor, id_producto) DO UPDATE SET
                    unidades = postventa.agregado_ventas.unidades + EXCLUDED.unidades,
                    monto = postventa.agregado_ventas.monto + EXCLUDED.monto,
                    actualizado_en = CURRENT_TIMESTAMP
                """, periodo, canal.name(), vendedor, producto, unidades, monto);
    }
}

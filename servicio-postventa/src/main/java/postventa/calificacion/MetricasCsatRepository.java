package postventa.calificacion;

import java.sql.Date;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import postventa.compartido.CanalVenta;

/**
 * Actualiza únicamente los acumuladores de CSAT.
 * Participa en la transacción que registra la calificación.
 */
@Repository
public class MetricasCsatRepository {

    private final JdbcTemplate jdbc;

    public MetricasCsatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementar(LocalDate fecha, CanalVenta canal, int puntaje) {
        jdbc.update("""
                INSERT INTO postventa.agregado_metricas
                    (fecha, canal, csat_suma, csat_cantidad, actualizado_en)
                VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (fecha, canal)
                DO UPDATE SET
                    csat_suma = postventa.agregado_metricas.csat_suma
                        + EXCLUDED.csat_suma,
                    csat_cantidad = postventa.agregado_metricas.csat_cantidad
                        + EXCLUDED.csat_cantidad,
                    actualizado_en = CURRENT_TIMESTAMP
                """,
                Date.valueOf(fecha),
                canal.name(),
                puntaje);
    }
}

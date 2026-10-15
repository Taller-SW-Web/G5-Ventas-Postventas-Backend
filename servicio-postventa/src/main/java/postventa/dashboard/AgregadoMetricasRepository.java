package postventa.dashboard;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import postventa.compartido.CanalVenta;

/**
 * Tabla postventa.agregado_metricas (H6): una fila por (fecha, canal). Se suma con upsert nativo para que dos
 * hilos que actualizan la misma fila no se pisen (guía 10.4).
 */
@Repository
public class AgregadoMetricasRepository {

    /** Columnas que se pueden sumar. El nombre de la columna nunca viene de afuera: sale de este enum. */
    enum Contador {
        PEDIDOS_PAGADOS("pedidos_pagados"),
        PEDIDOS_ANULADOS("pedidos_anulados"),
        PEDIDOS_ENTREGADOS("pedidos_entregados"),
        DEVOLUCIONES_APROBADAS("devoluciones_aprobadas"),
        MONTO_REEMBOLSADO_PEN("monto_reembolsado_pen"),
        RECLAMOS_REGISTRADOS("reclamos_registrados"),
        RECLAMOS_ATENDIDOS("reclamos_atendidos");

        final String columna;

        Contador(String columna) {
            this.columna = columna;
        }
    }

    /** Suma de las filas de un periodo. ultimaActualizacion es null si el periodo no tiene filas. */
    record Totales(long pedidosPagados, long pedidosAnulados, long pedidosEntregados, long devolucionesAprobadas,
                   BigDecimal montoReembolsadoPen, long csatSuma, long csatCantidad,
                   OffsetDateTime ultimaActualizacion) {}

    private final JdbcTemplate jdbc;

    public AgregadoMetricasRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sumar(LocalDate fecha, CanalVenta canal, Contador contador, Number valor) {
        String columna = contador.columna;
        jdbc.update("""
                INSERT INTO postventa.agregado_metricas (fecha, canal, %1$s, actualizado_en)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (fecha, canal) DO UPDATE SET
                    %1$s = postventa.agregado_metricas.%1$s + EXCLUDED.%1$s,
                    actualizado_en = CURRENT_TIMESTAMP
                """.formatted(columna), Date.valueOf(fecha), canal.name(), valor);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sumarCsat(LocalDate fecha, CanalVenta canal, int puntaje) {
        jdbc.update("""
                INSERT INTO postventa.agregado_metricas (fecha, canal, csat_suma, csat_cantidad, actualizado_en)
                VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (fecha, canal) DO UPDATE SET
                    csat_suma = postventa.agregado_metricas.csat_suma + EXCLUDED.csat_suma,
                    csat_cantidad = postventa.agregado_metricas.csat_cantidad + EXCLUDED.csat_cantidad,
                    actualizado_en = CURRENT_TIMESTAMP
                """, Date.valueOf(fecha), canal.name(), puntaje);
    }

    /** Una sola lectura de la tabla agregada; nunca se recorren pedidos ni expedientes (ESP-15 CA-06). */
    public Totales totales(LocalDate desde, LocalDate hasta, CanalVenta canal) {
        List<Object> parametros = new ArrayList<>(List.of(Date.valueOf(desde), Date.valueOf(hasta)));
        String filtroCanal = "";
        if (canal != null) {
            filtroCanal = " AND canal = ?";
            parametros.add(canal.name());
        }
        return jdbc.queryForObject("""
                SELECT coalesce(sum(pedidos_pagados), 0), coalesce(sum(pedidos_anulados), 0),
                       coalesce(sum(pedidos_entregados), 0), coalesce(sum(devoluciones_aprobadas), 0),
                       coalesce(sum(monto_reembolsado_pen), 0), coalesce(sum(csat_suma), 0),
                       coalesce(sum(csat_cantidad), 0), max(actualizado_en)
                FROM postventa.agregado_metricas
                WHERE fecha BETWEEN ? AND ?""" + filtroCanal,
                (rs, n) -> new Totales(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getBigDecimal(5), rs.getLong(6), rs.getLong(7),
                        rs.getObject(8, OffsetDateTime.class) == null ? null
                                : rs.getObject(8, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC)),
                parametros.toArray());
    }
}

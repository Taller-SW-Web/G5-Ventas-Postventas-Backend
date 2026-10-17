package postventa.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import postventa.compartido.CanalVenta;
import postventa.dashboard.AgregadoMetricasRepository.Contador;

/** Qué consulta arma el repositorio: filtros de periodo y canal (ESP-16) y lectura solo de la tabla agregada (ESP-15). */
@ExtendWith(MockitoExtension.class)
class AgregadoMetricasRepositoryTest {

    private static final LocalDate DESDE = LocalDate.parse("2026-09-01");
    private static final LocalDate HASTA = LocalDate.parse("2026-09-30");

    @Mock JdbcTemplate jdbc;
    @InjectMocks AgregadoMetricasRepository repositorio;

    @Test
    void esp16_ca02_conCanalFiltraPorEseCanalYPeriodo() {
        repositorio.totales(DESDE, HASTA, CanalVenta.CHATBOT);

        Consulta c = consultaEjecutada();
        assertThat(c.sql()).contains("WHERE fecha BETWEEN ? AND ?").contains("AND canal = ?");
        assertThat(c.parametros()).containsExactly(Date.valueOf(DESDE), Date.valueOf(HASTA), "CHATBOT");
    }

    @Test
    void esp16_ca03_sinCanalConsolidaTodosLosCanales() {
        repositorio.totales(DESDE, HASTA, null);

        Consulta c = consultaEjecutada();
        assertThat(c.sql()).contains("WHERE fecha BETWEEN ? AND ?").doesNotContain("canal = ?");
        assertThat(c.parametros()).containsExactly(Date.valueOf(DESDE), Date.valueOf(HASTA));
    }

    @Test
    void esp15_ca06_lasMetricasSoloLeenLaTablaAgregada() {
        repositorio.totales(DESDE, HASTA, null);

        String sql = consultaEjecutada().sql().toLowerCase();
        assertThat(sql).contains("from postventa.agregado_metricas")
                .doesNotContain("ventas.").doesNotContain(" join ");
    }

    @Test
    void esp15_ca01_sumarHaceUpsertSobreLaColumnaDelContador() {
        repositorio.sumar(DESDE, CanalVenta.RETAIL, Contador.PEDIDOS_PAGADOS, 1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(Date.valueOf(DESDE)), eq("RETAIL"), eq(1));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (fecha, canal) DO UPDATE")
                .contains("pedidos_pagados = postventa.agregado_metricas.pedidos_pagados + EXCLUDED.pedidos_pagados");
    }

    private record Consulta(String sql, List<Object> parametros) {}

    /** queryForObject(sql, mapper, parámetros...): Mockito entrega los varargs ya expandidos tras sql y mapper. */
    private Consulta consultaEjecutada() {
        Invocation llamada = mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("queryForObject"))
                .findFirst().orElseThrow();
        Object[] argumentos = llamada.getArguments();
        assertThat(argumentos[1]).isInstanceOf(RowMapper.class);
        return new Consulta((String) argumentos[0], List.of(argumentos).subList(2, argumentos.length));
    }
}

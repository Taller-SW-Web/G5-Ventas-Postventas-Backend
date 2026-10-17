package postventa.integracion.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import postventa.compartido.CanalVenta;
import postventa.compartido.SolicitudInvalidaException;
import postventa.dashboard.AgregadoMetricasRepository;
import postventa.dashboard.ProyeccionesService;
import postventa.dashboard.ProyeccionVentasService;
import postventa.dashboard.AgregadoVentasRepository;

/**
 * Comprueba el receptor con registro, validación y proyecciones reales.
 * Utiliza únicamente el PostgreSQL local f5-eventos-test.
 */
@EnabledIfEnvironmentVariable(named = "F5_POSTGRES_TEST", matches = "true")
class EventoPedidoPostgresTest {

    private static final Map<String, String> COLUMNAS = Map.of(
            "pedido.pagado", "pedidos_pagados",
            "pedido.anulado", "pedidos_anulados",
            "pedido.entregado", "pedidos_entregados");

    private static AnnotationConfigApplicationContext contexto;
    private static EventoPedidoService servicio;
    private static ProyeccionesService proyecciones;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepararContexto() {
        contexto = new AnnotationConfigApplicationContext(ConfiguracionLocal.class);
        servicio = contexto.getBean(EventoPedidoService.class);
        jdbc = contexto.getBean(JdbcTemplate.class);
        proyecciones = AopTestUtils.getUltimateTargetObject(
                contexto.getBean(ProyeccionesService.class));
    }

    @BeforeEach
    void restaurarProyecciones() {
        reset(proyecciones);
    }

    @AfterAll
    static void cerrar() {
        if (contexto != null) {
            contexto.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pedido.pagado",
            "pedido.anulado",
            "pedido.entregado"
    })
    void eventoNuevoSumaUnaVezAunqueSeRepita(String tipo) {
        EventoPedidoPayload evento = evento(tipo);
        LocalDate dia = diaUtc(evento);
        long antes = contador(evento, dia);

        assertThat(servicio.recibir(evento)).isTrue();
        assertThat(servicio.recibir(evento)).isFalse();

        assertThat(registros(evento)).isEqualTo(1L);
        assertThat(contador(evento, dia) - antes).isEqualTo(1L);
    }

    @Test
    void doceRecepcionesConcurrentesSoloSumanUnaVez() throws Exception {
        EventoPedidoPayload evento = evento("pedido.pagado");
        long antes = contador(evento, diaUtc(evento));
        ExecutorService ejecutor = Executors.newFixedThreadPool(12);
        CountDownLatch inicio = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();

        try {
            for (int i = 0; i < 12; i++) {
                resultados.add(ejecutor.submit(() -> {
                    if (!inicio.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("No se recibió el inicio");
                    }
                    return servicio.recibir(evento);
                }));
            }

            inicio.countDown();
            int nuevos = 0;

            for (Future<Boolean> resultado : resultados) {
                Boolean registrado = resultado.get(20, TimeUnit.SECONDS);
                assertThat(registrado).isNotNull();
                if (Boolean.TRUE.equals(registrado)) {
                    nuevos++;
                }
            }

            assertThat(nuevos).isEqualTo(1);
            assertThat(registros(evento)).isEqualTo(1L);
            assertThat(contador(evento, diaUtc(evento)) - antes).isEqualTo(1L);
        } finally {
            ejecutor.shutdownNow();
            ejecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void falloDespuesDeSumarRevierteEventoYMetricasYPermiteReintentar() {
        EventoPedidoPayload evento = evento("pedido.anulado");
        long antes = contador(evento, diaUtc(evento));

        // Primero ejecuta el SQL real y luego falla dentro de la transacción.
        doAnswer(invocacion -> {
            invocacion.callRealMethod();
            throw new IllegalStateException("Fallo posterior a la suma");
        }).when(proyecciones).aplicarEventoPedido(
                anyString(), any(OffsetDateTime.class), any(CanalVenta.class));

        assertThatThrownBy(() -> servicio.recibir(evento))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fallo posterior a la suma");

        assertThat(registros(evento)).isZero();
        assertThat(contador(evento, diaUtc(evento))).isEqualTo(antes);

        reset(proyecciones);

        assertThat(servicio.recibir(evento)).isTrue();
        assertThat(registros(evento)).isEqualTo(1L);
        assertThat(contador(evento, diaUtc(evento)) - antes).isEqualTo(1L);
    }

    @Test
    void usaElDiaUtcDelEventoYNoElDiaDeSuZonaOriginal() {
        EventoPedidoPayload evento = evento("pedido.entregado");
        LocalDate diaOriginal = evento.fecha().toLocalDate();
        LocalDate diaUtc = diaUtc(evento);
        long antesOriginal = contador(evento, diaOriginal);
        long antesUtc = contador(evento, diaUtc);

        assertThat(diaUtc).isEqualTo(diaOriginal.plusDays(1));

        servicio.recibir(evento);

        assertThat(contador(evento, diaOriginal)).isEqualTo(antesOriginal);
        assertThat(contador(evento, diaUtc) - antesUtc).isEqualTo(1L);
    }

    @Test
    void eventoInvalidoNoSeMarcaComoRecibido() {
        EventoPedidoPayload evento = evento("pedido.desconocido");

        assertThatThrownBy(() -> servicio.recibir(evento))
                .isInstanceOf(SolicitudInvalidaException.class);

        assertThat(registros(evento)).isZero();
    }

    @Test
    void pagoSumaLasVentasPorProductoUnaVezAunqueSeRepita() {
        EventoPedidoPayload original = evento("pedido.pagado");
        UUID vendedor = UUID.randomUUID();
        EventoPedidoPayload evento = new EventoPedidoPayload(original.idEvento(), original.tipo(),
                original.pedidoId(), original.fecha(), new EventoPedidoPayload.Datos(
                        CanalVenta.RETAIL, original.datos().clienteId(), vendedor, new BigDecimal("25.00"),
                        "PEN", null, List.of(new EventoPedidoPayload.Item("PROD-42", 2,
                                new BigDecimal("25.00"))), null));

        assertThat(servicio.recibir(evento)).isTrue();
        assertThat(servicio.recibir(evento)).isFalse();

        Map<String, Object> venta = jdbc.queryForMap("""
                SELECT periodo, unidades, monto FROM postventa.agregado_ventas
                WHERE canal = 'RETAIL' AND id_vendedor = ? AND id_producto = 'PROD-42'
                """, vendedor);
        assertThat(venta.get("periodo")).isEqualTo(diaUtc(evento).toString().substring(0, 7));
        assertThat(((Number) venta.get("unidades")).longValue()).isEqualTo(2);
        assertThat((BigDecimal) venta.get("monto")).isEqualByComparingTo("25.00");
    }

    private static EventoPedidoPayload evento(String tipo) {
        LocalDate dia = LocalDate.of(2100, 1, 1)
                .plusDays(ThreadLocalRandom.current().nextLong(365000));

        return new EventoPedidoPayload(
                UUID.randomUUID(),
                tipo,
                "PED-2026-00981",
                OffsetDateTime.of(dia, LocalTime.of(23, 30), ZoneOffset.ofHours(-5)),
                new EventoPedidoPayload.Datos(
                        CanalVenta.MARKETPLACE,
                        "11111111-1111-1111-1111-111111111111",
                        null,
                        new BigDecimal("129.90"),
                        "PEN",
                        tipo.equals("pedido.anulado") ? "Solicitud del cliente" : null,
                        tipo.equals("pedido.pagado")
                                ? List.of(new EventoPedidoPayload.Item(
                                        "prd-013-01", 1, new BigDecimal("129.90")))
                                : null,
                        null));
    }

    private static LocalDate diaUtc(EventoPedidoPayload evento) {
        return evento.fecha().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    private static long registros(EventoPedidoPayload evento) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM postventa.evento_recibido WHERE id_evento = ?",
                Long.class,
                evento.idEvento()));
    }

    private static long contador(EventoPedidoPayload evento, LocalDate dia) {
        // El nombre de columna procede exclusivamente del mapa fijo de la prueba.
        String columna = Objects.requireNonNull(COLUMNAS.get(evento.tipo()));
        return Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT COALESCE(
                    (SELECT %s FROM postventa.agregado_metricas
                     WHERE fecha = ? AND canal = ?), 0)
                """.formatted(columna),
                Long.class,
                Date.valueOf(dia),
                evento.datos().canal().name()));
    }

    /**
     * Configuración exclusiva de prueba; no carga application.yml ni Supabase.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({
            EventoPedidoService.class,
            EventoRecibidoRepository.class,
            AgregadoMetricasRepository.class,
            AgregadoVentasRepository.class,
            ProyeccionVentasService.class
    })
    static class ConfiguracionLocal {

        /** Los avisos de reemplazo (F3) no se prueban aquí. */
        @Bean
        postventa.devolucion.DevolucionService devolucionService() {
            return org.mockito.Mockito.mock(postventa.devolucion.DevolucionService.class);
        }

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource fuente = new DriverManagerDataSource();
            fuente.setDriverClassName("org.postgresql.Driver");
            fuente.setUrl("jdbc:postgresql://127.0.0.1:55432/f5_eventos_test"
                    + "?connectTimeout=5&socketTimeout=30");
            fuente.setUsername("f5_test");
            fuente.setPassword("f5_test_local");

            JdbcTemplate esquema = new JdbcTemplate(fuente);
            esquema.execute("CREATE SCHEMA IF NOT EXISTS postventa");
            esquema.execute("""
                    CREATE TABLE IF NOT EXISTS postventa.evento_recibido (
                        id_evento UUID PRIMARY KEY,
                        tipo VARCHAR(40) NOT NULL,
                        recibido_en TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
            esquema.execute("""
                    CREATE TABLE IF NOT EXISTS postventa.agregado_metricas (
                        fecha DATE NOT NULL,
                        canal VARCHAR(30) NOT NULL,
                        csat_suma BIGINT NOT NULL DEFAULT 0,
                        csat_cantidad BIGINT NOT NULL DEFAULT 0,
                        actualizado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
                        PRIMARY KEY (fecha, canal)
                    )
                    """);

            // Amplía solo las tablas de la base local de prueba.
            for (String columna : COLUMNAS.values()) {
                esquema.execute(
                        "ALTER TABLE postventa.agregado_metricas ADD COLUMN IF NOT EXISTS "
                                + columna + " BIGINT NOT NULL DEFAULT 0");
            }

            esquema.execute("""
                    CREATE TABLE IF NOT EXISTS postventa.agregado_ventas (
                        periodo VARCHAR(7) NOT NULL,
                        canal VARCHAR(30) NOT NULL,
                        id_vendedor UUID NOT NULL,
                        id_producto VARCHAR(64) NOT NULL,
                        unidades BIGINT NOT NULL DEFAULT 0,
                        monto NUMERIC(18, 2) NOT NULL DEFAULT 0,
                        actualizado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
                        PRIMARY KEY (periodo, canal, id_vendedor, id_producto)
                    )
                    """);

            return fuente;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource fuente) {
            return new JdbcTemplate(fuente);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource fuente) {
            return new JdbcTransactionManager(fuente);
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }

        @Bean
        ValidadorEventoPedido validadorEventoPedido(
                LocalValidatorFactoryBean validator) {
            return new ValidadorEventoPedido(validator);
        }

        @Bean
        ProyeccionesService proyeccionesService(
                AgregadoMetricasRepository agregados) {
            return spy(new ProyeccionesService(agregados));
        }
    }
}
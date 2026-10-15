package postventa.calificacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import postventa.calificacion.dto.RegistrarCsatRequest;
import postventa.compartido.CanalVenta;
import postventa.compartido.CodigoPublico;
import postventa.compartido.ConflictoException;
import postventa.integracion.PedidoResumen;
import postventa.integracion.VentasClient;

/**
 * Verifica CSAT con JPA, JDBC y PostgreSQL reales en la base local de pruebas.
 * Solo sustituye la consulta a Ventas y permite simular un fallo de métricas.
 */
@EnabledIfEnvironmentVariable(named = "F5_POSTGRES_TEST", matches = "true")
class CalificacionPostgresTest {

    private static final UUID CLIENTE =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static AnnotationConfigApplicationContext contexto;
    private static CalificacionService servicio;
    private static CalificacionRepository repositorio;
    private static MetricasCsatRepository metricas;
    private static VentasClient ventas;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepararContexto() {
        contexto = new AnnotationConfigApplicationContext(ConfiguracionLocal.class);
        servicio = contexto.getBean(CalificacionService.class);
        repositorio = contexto.getBean(CalificacionRepository.class);
        metricas = AopTestUtils.getUltimateTargetObject(
                contexto.getBean(MetricasCsatRepository.class));
        ventas = contexto.getBean(VentasClient.class);
        jdbc = contexto.getBean(JdbcTemplate.class);
    }

    @BeforeEach
    void prepararDependencias() {
        reset(ventas, metricas);

        when(ventas.consultarPedido(anyString())).thenAnswer(invocacion ->
                Optional.of(new PedidoResumen(
                        invocacion.getArgument(0),
                        PedidoResumen.ENTREGADO,
                        CLIENTE,
                        null,
                        null,
                        List.of(),
                        null)));
    }

    @AfterAll
    static void cerrar() {
        if (contexto != null) {
            contexto.close();
        }
    }

    @Test
    void duplicadoConservaUnaCalificacionYUnaSolaSuma() {
        RegistrarCsatRequest solicitud = solicitudNueva();
        Acumuladores antes = acumuladores();

        servicio.registrar(solicitud, CLIENTE);

        assertThatThrownBy(() -> servicio.registrar(solicitud, CLIENTE))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo")
                .isEqualTo("CSAT_ALREADY_SUBMITTED");

        assertThat(contar(solicitud)).isEqualTo(1L);
        assertThat(acumuladores().cantidad() - antes.cantidad()).isEqualTo(1L);
        assertThat(acumuladores().suma() - antes.suma()).isEqualTo(5L);
    }

    @Test
    void veinteSolicitudesSimultaneasSoloRegistranUnaCalificacion()
            throws Exception {

        RegistrarCsatRequest solicitud = solicitudNueva();
        Acumuladores antes = acumuladores();
        ExecutorService ejecutor = Executors.newFixedThreadPool(20);
        CountDownLatch inicio = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();

        try {
            for (int i = 0; i < 20; i++) {
                resultados.add(ejecutor.submit(() -> {
                    if (!inicio.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("No se recibió el inicio");
                    }

                    try {
                        servicio.registrar(solicitud, CLIENTE);
                        return true;
                    } catch (ConflictoException error) {
                        assertThat(error.getCodigo())
                                .isEqualTo("CSAT_ALREADY_SUBMITTED");
                        return false;
                    }
                }));
            }

            inicio.countDown();
            int registradas = 0;

            for (Future<Boolean> resultado : resultados) {
                if (Boolean.TRUE.equals(resultado.get(20, TimeUnit.SECONDS))) {
                    registradas++;
                }
            }

            assertThat(registradas).isEqualTo(1);
            assertThat(contar(solicitud)).isEqualTo(1L);
            assertThat(acumuladores().cantidad() - antes.cantidad()).isEqualTo(1L);
            assertThat(acumuladores().suma() - antes.suma()).isEqualTo(5L);
        } finally {
            ejecutor.shutdownNow();
            ejecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void falloDeMetricasRevierteLaCalificacion() {
        RegistrarCsatRequest solicitud = solicitudNueva();
        Acumuladores antes = acumuladores();

        doThrow(new IllegalStateException("Fallo de métricas de prueba"))
                .when(metricas).incrementar(
                        any(LocalDate.class), any(CanalVenta.class), anyInt());

        assertThatThrownBy(() -> servicio.registrar(solicitud, CLIENTE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fallo de métricas de prueba");

        assertThat(contar(solicitud)).isZero();
        assertThat(acumuladores()).isEqualTo(antes);
    }

    @Test
    void insercionDirectaSinTransaccionEsRechazada() {
        RegistrarCsatRequest solicitud = solicitudNueva();

        assertThatThrownBy(() -> repositorio.insertarSiNoExiste(
                CodigoPublico.id(solicitud.pedidoId()),
                5,
                solicitud.comentario(),
                solicitud.canal().name(),
                LocalDate.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(contar(solicitud)).isZero();
    }

    private static RegistrarCsatRequest solicitudNueva() {
        long id = Math.max(1L, UUID.randomUUID().getMostSignificantBits()
                & Long.MAX_VALUE);

        return new RegistrarCsatRequest(
                CodigoPublico.pedido(id, 2026),
                CLIENTE,
                CanalVenta.MARKETPLACE,
                5,
                "Prueba local de concurrencia");
    }

    private static long contar(RegistrarCsatRequest solicitud) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM postventa.calificacion WHERE id_pedido = ?",
                Long.class,
                CodigoPublico.id(solicitud.pedidoId())));
    }

    private static Acumuladores acumuladores() {
        return Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(csat_suma), 0), COALESCE(SUM(csat_cantidad), 0)
                FROM postventa.agregado_metricas
                WHERE fecha = ? AND canal = 'MARKETPLACE'
                """,
                (fila, numero) -> new Acumuladores(
                        fila.getLong(1), fila.getLong(2)),
                Date.valueOf(LocalDate.now(ZoneOffset.UTC))));
    }

    private record Acumuladores(long suma, long cantidad) {
    }

    /**
     * Contexto limitado a CSAT y a la base local; no carga application.yml.
     * Las tablas contienen las columnas necesarias para estas operaciones.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CalificacionRepository.class)
    @Import(CalificacionService.class)
    static class ConfiguracionLocal {

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
                    CREATE TABLE IF NOT EXISTS postventa.calificacion (
                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        id_pedido BIGINT NOT NULL UNIQUE,
                        puntaje INTEGER NOT NULL CHECK (puntaje BETWEEN 1 AND 5),
                        comentario TEXT,
                        canal VARCHAR(30) NOT NULL
                            CHECK (canal IN ('MARKETPLACE', 'CHATBOT', 'RETAIL')),
                        fecha DATE NOT NULL DEFAULT current_date
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
            return fuente;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(
                DataSource fuente) {

            LocalContainerEntityManagerFactoryBean fabrica =
                    new LocalContainerEntityManagerFactoryBean();
            fabrica.setDataSource(fuente);
            fabrica.setPackagesToScan(Calificacion.class.getPackageName());
            fabrica.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            fabrica.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return fabrica;
        }

        @Bean
        PlatformTransactionManager transactionManager(
                EntityManagerFactory fabrica, DataSource fuente) {

            JpaTransactionManager gestor = new JpaTransactionManager(fabrica);
            gestor.setDataSource(fuente);
            return gestor;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource fuente) {
            return new JdbcTemplate(fuente);
        }

        @Bean
        MetricasCsatRepository metricasCsatRepository(JdbcTemplate jdbc) {
            return spy(new MetricasCsatRepository(jdbc));
        }

        @Bean
        VentasClient ventasClient() {
            return mock(VentasClient.class);
        }
    }
}
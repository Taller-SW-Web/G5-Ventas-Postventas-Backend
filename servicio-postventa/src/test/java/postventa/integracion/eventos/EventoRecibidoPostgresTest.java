package postventa.integracion.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pruebas contra el PostgreSQL local del contenedor f5-eventos-test.
 *
 * Se activan explícitamente para que la suite habitual no requiera Docker.
 * No carga application.yml ni utiliza las credenciales de Supabase.
 */
@EnabledIfEnvironmentVariable(
        named = "F5_POSTGRES_TEST",
        matches = "true")
class EventoRecibidoPostgresTest {

    private static AnnotationConfigApplicationContext contexto;
    private static EventoRecibidoRepository repositorio;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transacciones;

    @BeforeAll
    static void preparar() {
        contexto = new AnnotationConfigApplicationContext(
                ConfiguracionLocal.class);

        repositorio = contexto.getBean(EventoRecibidoRepository.class);
        jdbc = contexto.getBean(JdbcTemplate.class);

        transacciones = new TransactionTemplate(
                contexto.getBean(PlatformTransactionManager.class));

        // Solo prepara la estructura necesaria en la base local de pruebas.
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS postventa");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS postventa.evento_recibido (
                    id_evento UUID PRIMARY KEY,
                    tipo VARCHAR(40) NOT NULL,
                    recibido_en TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
    }

    @AfterAll
    static void cerrar() {
        if (contexto != null) {
            contexto.close();
        }
    }

    @Test
    void eventoRepetidoSoloSeRegistraUnaVez() {
        UUID idEvento = UUID.randomUUID();

        Boolean primero = transacciones.execute(estado ->
                repositorio.registrarSiNuevo(idEvento, "pedido.pagado"));

        Boolean repetido = transacciones.execute(estado ->
                repositorio.registrarSiNuevo(idEvento, "pedido.pagado"));

        assertThat(primero).isTrue();
        assertThat(repetido).isFalse();
        assertThat(contar(idEvento)).isEqualTo(1L);
    }

    @Test
    void recepcionesSimultaneasSoloPermitenUnRegistroNuevo() throws Exception {
        UUID idEvento = UUID.randomUUID();
        int solicitudes = 12;

        ExecutorService ejecutor = Executors.newFixedThreadPool(solicitudes);
        CountDownLatch inicio = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();

        try {
            for (int i = 0; i < solicitudes; i++) {
                resultados.add(ejecutor.submit(() -> {
                    if (!inicio.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "No se recibió la señal de inicio");
                    }

                    return transacciones.execute(estado ->
                            repositorio.registrarSiNuevo(
                                    idEvento,
                                    "pedido.entregado"));
                }));
            }

            inicio.countDown();

            int nuevos = 0;
            int repetidos = 0;

            for (Future<Boolean> resultado : resultados) {
                Boolean registrado = resultado.get(15, TimeUnit.SECONDS);

                assertThat(registrado).isNotNull();

                if (Boolean.TRUE.equals(registrado)) {
                    nuevos++;
                } else {
                    repetidos++;
                }
            }

            assertThat(nuevos).isEqualTo(1);
            assertThat(repetidos).isEqualTo(solicitudes - 1);
            assertThat(contar(idEvento)).isEqualTo(1L);
        } finally {
            ejecutor.shutdownNow();
            ejecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void falloPosteriorRevierteElRegistroYPermiteReintentar() {
        UUID idEvento = UUID.randomUUID();

        assertThatThrownBy(() ->
                transacciones.executeWithoutResult(estado -> {
                    boolean nuevo = repositorio.registrarSiNuevo(
                            idEvento,
                            "pedido.anulado");

                    assertThat(nuevo).isTrue();

                    // Simula un fallo después de registrar el evento.
                    throw new IllegalStateException("Fallo al aplicar el evento");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fallo al aplicar el evento");

        assertThat(contar(idEvento)).isZero();

        Boolean reintento = transacciones.execute(estado ->
                repositorio.registrarSiNuevo(idEvento, "pedido.anulado"));

        assertThat(reintento).isTrue();
        assertThat(contar(idEvento)).isEqualTo(1L);
    }

    @Test
    void registrarSinTransaccionEsRechazado() {
        UUID idEvento = UUID.randomUUID();

        assertThatThrownBy(() ->
                repositorio.registrarSiNuevo(idEvento, "pedido.pagado"))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(contar(idEvento)).isZero();
    }

    private static long contar(UUID idEvento) {
        return Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM postventa.evento_recibido
                WHERE id_evento = ?
                """,
                Long.class,
                idEvento));
    }

    /**
     * Contexto mínimo: repositorio real y transacciones reales.
     * La conexión queda fijada al PostgreSQL local de pruebas.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(EventoRecibidoRepository.class)
    static class ConfiguracionLocal {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource fuente = new DriverManagerDataSource();

            fuente.setDriverClassName("org.postgresql.Driver");
            fuente.setUrl(
                    "jdbc:postgresql://127.0.0.1:55432/f5_eventos_test"
                            + "?connectTimeout=5&socketTimeout=20");
            fuente.setUsername("f5_test");
            fuente.setPassword("f5_test_local");

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
    }
}

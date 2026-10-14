package postventa.integracion.eventos;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra los eventos recibidos y evita procesar dos veces el mismo UUID.
 */
@Repository
public class EventoRecibidoRepository {

    private static final String REGISTRAR = """
            INSERT INTO postventa.evento_recibido (id_evento, tipo)
            VALUES (?, ?)
            ON CONFLICT (id_evento) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public EventoRecibidoRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * Devuelve true si el evento es nuevo y false si ya estaba registrado.
     *
     * Exige una transacción existente para que el registro y las proyecciones
     * se confirmen o reviertan juntos.
     *
     * La clave primaria resuelve también las recepciones simultáneas
     * del mismo evento.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean registrarSiNuevo(UUID idEvento, String tipo) {
        Objects.requireNonNull(idEvento);
        Objects.requireNonNull(tipo);

        return jdbc.update(REGISTRAR, idEvento, tipo) == 1;
    }
}

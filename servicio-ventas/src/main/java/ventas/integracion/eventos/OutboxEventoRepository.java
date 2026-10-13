package ventas.integracion.eventos;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventoRepository extends JpaRepository<OutboxEvento, UUID> {

    /** Pendientes que todavía se pueden reintentar (intentos < máximo), del más antiguo al más nuevo. */
    List<OutboxEvento> findTop50ByEnviadoEnIsNullAndIntentosLessThanOrderByCreadoEnAsc(int maximo);
}

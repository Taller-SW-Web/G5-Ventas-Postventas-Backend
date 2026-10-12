package ventas.integracion.eventos;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventoRepository extends JpaRepository<OutboxEvento, UUID> {

    List<OutboxEvento> findTop50ByEnviadoEnIsNullOrderByCreadoEnAsc();
}

package ventas.integracion.eventos;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventoRepository extends JpaRepository<OutboxEvento, Long> {

    List<OutboxEvento> findTop50ByEnviadoEnIsNullOrderByCreadoEnAsc();
}

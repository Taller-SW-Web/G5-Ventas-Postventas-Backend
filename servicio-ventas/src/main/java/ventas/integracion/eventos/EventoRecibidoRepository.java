package ventas.integracion.eventos;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventoRecibidoRepository extends JpaRepository<EventoRecibido, String> {

    boolean existsByIdEventoAndOrigen(String idEvento, OrigenEvento origen);
}

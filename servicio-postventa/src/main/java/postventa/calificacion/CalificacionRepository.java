package postventa.calificacion;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistencia de calificaciones CSAT.
 */
public interface CalificacionRepository
        extends JpaRepository<Calificacion, Long> {

    boolean existsByIdPedido(Long idPedido);

    Optional<Calificacion> findByIdPedido(Long idPedido);
}

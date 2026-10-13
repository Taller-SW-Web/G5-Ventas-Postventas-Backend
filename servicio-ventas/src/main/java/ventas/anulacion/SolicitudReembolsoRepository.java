package ventas.anulacion;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SolicitudReembolsoRepository extends JpaRepository<SolicitudReembolso, Long> {

    Optional<SolicitudReembolso> findByIdAnulacion(Long idAnulacion);

    List<SolicitudReembolso> findTop20ByEstadoOrderByCreadoEnAsc(EstadoSolicitudReembolso estado);
}

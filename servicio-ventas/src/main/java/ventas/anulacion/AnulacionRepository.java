package ventas.anulacion;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnulacionRepository extends JpaRepository<Anulacion, Long> {

    Optional<Anulacion> findByIdPedido(Long idPedido);

    Page<Anulacion> findByEstado(EstadoAnulacion estado, Pageable pageable);
}


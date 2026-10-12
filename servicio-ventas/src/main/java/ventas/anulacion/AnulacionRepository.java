package ventas.anulacion;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnulacionRepository extends JpaRepository<Anulacion, Long> {

    Optional<Anulacion> findByIdPedido(Long idPedido);
}

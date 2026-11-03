package ventas.anulacion;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AnulacionRepository extends JpaRepository<Anulacion, Long>, JpaSpecificationExecutor<Anulacion> {

    Optional<Anulacion> findByIdPedido(Long idPedido);

    Page<Anulacion> findByEstado(EstadoAnulacion estado, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "pedido")
    Page<Anulacion> findAll(Specification<Anulacion> spec, Pageable pageable);
}

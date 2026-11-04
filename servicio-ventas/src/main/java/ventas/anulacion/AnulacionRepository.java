package ventas.anulacion;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AnulacionRepository extends JpaRepository<Anulacion, Long>, JpaSpecificationExecutor<Anulacion> {

    Optional<Anulacion> findByIdPedido(Long idPedido);

    Page<Anulacion> findByEstado(EstadoAnulacion estado, Pageable pageable);

    /** Solo el id del pedido, sin cargar la entidad: 1.9 lo usa para bloquear el pedido antes de leer la anulación. */
    @Query("select a.idPedido from Anulacion a where a.id = :id")
    Optional<Long> idPedidoDe(@Param("id") Long id);

    /** La anulación vigente con SELECT ... FOR UPDATE; se pide después de bloquear su pedido (orden pedido -> anulación). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Anulacion a where a.id = :id")
    Optional<Anulacion> bloquear(@Param("id") Long id);

    @Override
    @EntityGraph(attributePaths = "pedido")
    Page<Anulacion> findAll(Specification<Anulacion> spec, Pageable pageable);
}

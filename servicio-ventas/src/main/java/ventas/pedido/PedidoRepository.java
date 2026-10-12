package ventas.pedido;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PedidoRepository extends JpaRepository<Pedido, Long>, JpaSpecificationExecutor<Pedido> {

    Optional<Pedido> findByCodigo(String codigo);

    /** Para la idempotencia de 1.1: trae los ítems para comparar el payload. */
    @EntityGraph(attributePaths = "detalles")
    Optional<Pedido> findByIdempotencyKey(String idempotencyKey);

    Page<Pedido> findByIdCliente(UUID idCliente, Pageable pagina);

    Page<Pedido> findByIdClienteAndEstado(UUID idCliente, EstadoPedido estado, Pageable pagina);
}

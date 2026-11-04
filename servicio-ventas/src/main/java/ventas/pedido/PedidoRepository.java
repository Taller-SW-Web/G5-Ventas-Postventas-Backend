package ventas.pedido;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PedidoRepository extends JpaRepository<Pedido, Long>, JpaSpecificationExecutor<Pedido> {

    Optional<Pedido> findByCodigo(String codigo);

    /**
     * El pedido con SELECT ... FOR UPDATE, dentro de la transacción que va a cambiarlo. La notificación de pago
     * (1.2), la anulación (1.7) y las anulaciones automáticas de Productos se esperan entre sí: la segunda ve el
     * estado que dejó la primera y nunca se pisan (pago y anulación concurrentes terminan en un solo reembolso).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pedido p where p.id = :id")
    Optional<Pedido> bloquear(@Param("id") Long id);

    /** Igual que bloquear, por código público (PED-AAAA-NNNNN). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pedido p where p.codigo = :codigo")
    Optional<Pedido> bloquearPorCodigo(@Param("codigo") String codigo);

    /** Para la idempotencia de 1.1: trae los ítems para comparar el payload. */
    @EntityGraph(attributePaths = "detalles")
    Optional<Pedido> findByIdempotencyKey(String idempotencyKey);

    Page<Pedido> findByIdCliente(UUID idCliente, Pageable pagina);

    Page<Pedido> findByIdClienteAndEstado(UUID idCliente, EstadoPedido estado, Pageable pagina);

    /** La orden de reemplazo vigente (no anulada) de una devolución de M2, para no crear dos (F3). */
    @EntityGraph(attributePaths = "detalles")
    Optional<Pedido> findFirstByDevolucionIdAndEstadoNotOrderByIdDesc(String devolucionId, EstadoPedido estado);

    /** 1.14: pedidos de un tipo creados en [inicio, fin), todos los canales. */
    List<FilaKpi> findByTipoAndCreadoEnGreaterThanEqualAndCreadoEnLessThan(
            TipoPedido tipo, OffsetDateTime inicio, OffsetDateTime fin);

    /** 1.14: igual que el anterior, de un solo canal. */
    List<FilaKpi> findByTipoAndCanalAndCreadoEnGreaterThanEqualAndCreadoEnLessThan(
            TipoPedido tipo, CanalVenta canal, OffsetDateTime inicio, OffsetDateTime fin);
}

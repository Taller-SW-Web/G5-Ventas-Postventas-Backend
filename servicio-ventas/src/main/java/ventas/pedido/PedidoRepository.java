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

    /**
     * Contrato H5 con Productos (G6): pedidos que pueden tener una reserva, un consumo o un cupón pendiente de
     * resultado, o una reserva que puede vencer: CREADO y PAGADO, y las órdenes de reemplazo EN_PREPARACION (la venta
     * normal EN_PREPARACION ya consumió su reserva y no tiene nada pendiente). Del más nuevo al más antiguo, para que
     * un lote lleno de pedidos viejos nunca deje sin seguimiento a los recién creados. Lo recorre SeguimientoProductos.
     */
    @Query("select p from Pedido p where p.creadoEn >= :desde and ("
            + "(p.canal <> :tienda and (p.estado in :estados or (p.estado = :enPreparacion and p.tipo = :reemplazo)))"
            + " or (p.canal = :tienda and p.estado = :entregado))"
            + " order by p.id desc")
    List<Pedido> enSeguimientoProductos(@Param("estados") List<EstadoPedido> estados,
                                        @Param("enPreparacion") EstadoPedido enPreparacion,
                                        @Param("reemplazo") TipoPedido reemplazo,
                                        @Param("tienda") CanalVenta tienda,
                                        @Param("entregado") EstadoPedido entregado,
                                        @Param("desde") OffsetDateTime desde,
                                        Pageable pagina);

    /** Contrato H5 D-07: el pedido con sus ítems, para reenviar la reserva con las mismas líneas. */
    @EntityGraph(attributePaths = "detalles")
    Optional<Pedido> findConDetallesByCodigo(String codigo);

    /** 1.15: Pedidos asignados a una tienda física listos para retiro (Pickup). */
    @EntityGraph(attributePaths = "detalles")
    @Query("select p from Pedido p where p.tiendaId = :tiendaId and (p.modalidadEnvio = 'RECOJO_TIENDA' or p.modalidadEnvio = 'TIENDA' or p.modalidadEnvio is null) and p.estado in :estados order by p.creadoEn desc")
    List<Pedido> buscarParaPickup(@Param("tiendaId") String tiendaId, @Param("estados") List<EstadoPedido> estados);
}

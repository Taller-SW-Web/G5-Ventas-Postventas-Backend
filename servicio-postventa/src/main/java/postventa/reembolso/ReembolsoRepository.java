package postventa.reembolso;

import java.util.List;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReembolsoRepository extends JpaRepository<Reembolso, Long> {
    Optional<Reembolso> findByIdempotencyKey(String clave);

    Optional<Reembolso> findByTipoOrigenAndIdOrigen(OrigenReembolso tipoOrigen, long idOrigen);

    /**
     * PostgreSQL advisory lock por pedido. Cubre incluso el primer reembolso, cuando aún no existen filas
     * que un PESSIMISTIC_WRITE pudiera bloquear.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(cast(:pedido as bigint))", nativeQuery = true)
    int bloquearSaldoPedido(@Param("pedido") long pedido);

    /** Filas del pedido bloqueadas antes de sumar en Java; evita que dos extornos comprometan el mismo saldo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reembolso r where r.idPedido = :pedido")
    List<Reembolso> bloquearReembolsosDelPedido(@Param("pedido") long pedido);
}

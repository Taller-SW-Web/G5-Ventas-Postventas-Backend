package postventa.reembolso;

import java.math.BigDecimal;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReembolsoRepository extends JpaRepository<Reembolso, Long> {
    Optional<Reembolso> findByIdempotencyKey(String clave);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select coalesce(sum(r.monto), 0) from Reembolso r where r.idPedido=:pedido and r.estado in (postventa.reembolso.EstadoReembolso.EXITOSO, postventa.reembolso.EstadoReembolso.PENDIENTE)")
    BigDecimal saldoComprometido(@Param("pedido") long pedido);
}

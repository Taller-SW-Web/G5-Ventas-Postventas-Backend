package ventas.integracion.envios;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificacionEnvioRepository extends JpaRepository<NotificacionEnvio, UUID> {

    /** Pendientes que todavía se pueden reintentar, del más antiguo al más nuevo. */
    List<NotificacionEnvio> findTop50ByEnviadoEnIsNullAndIntentosLessThanOrderByCreadoEnAsc(int maximo);

    List<NotificacionEnvio> findByIdPedidoOrderByCreadoEnAsc(Long idPedido);
}

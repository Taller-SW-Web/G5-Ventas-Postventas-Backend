package ventas.pedido;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HistorialEstadoRepository extends JpaRepository<HistorialEstado, Long> {

    List<HistorialEstado> findByPedidoIdOrderByFechaHoraAsc(Long idPedido);
}

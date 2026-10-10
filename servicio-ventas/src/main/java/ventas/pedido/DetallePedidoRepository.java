package ventas.pedido;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DetallePedidoRepository extends JpaRepository<DetallePedido, Long> {

    List<DetallePedido> findByPedidoId(Long idPedido);
}

package ventas.pedido;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ventas.compartido.NoImplementadoException;

@RestController
@RequestMapping("/api/v1/pedidos")
public class PedidoController {

    @PostMapping
    public ResponseEntity<Void> crear() {
        throw new NoImplementadoException("1.1 Crear pedido");
    }

    @PostMapping("/{pedidoId}/pagos/notificacion")
    public ResponseEntity<Void> notificarPago() {
        throw new NoImplementadoException("1.2 Notificación de pago");
    }

    @GetMapping("/{pedidoId}")
    public ResponseEntity<Void> consultar() {
        throw new NoImplementadoException("1.3 Consultar pedido");
    }

    @GetMapping
    public ResponseEntity<Void> listar() {
        throw new NoImplementadoException("1.4 Listar pedidos");
    }

    @PatchMapping("/{pedidoId}/estado")
    public ResponseEntity<Void> cambiarEstado() {
        throw new NoImplementadoException("1.5 Cambiar estado");
    }

    @PostMapping("/{pedidoId}/eventos-logistica")
    public ResponseEntity<Void> eventoLogistica() {
        throw new NoImplementadoException("1.6 Eventos de logística");
    }
}

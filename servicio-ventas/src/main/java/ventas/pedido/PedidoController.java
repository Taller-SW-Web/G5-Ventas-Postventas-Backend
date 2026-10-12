package ventas.pedido;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ventas.compartido.NoImplementadoException;
import ventas.pedido.dto.CrearPedidoRequest;
import ventas.pedido.dto.CrearPedidoResponse;
import ventas.pedido.dto.MisPedidosResponse;

@RestController
@RequestMapping("/api/v1/pedidos")
public class PedidoController {

    private final PedidoService pedidoService;
    private final CrearPedidoService crearPedidoService;
    private final boolean seguridadHabilitada;
    private final UUID clientePrueba;

    public PedidoController(PedidoService pedidoService,
                            CrearPedidoService crearPedidoService,
                            @Value("${seguridad.habilitada:true}") boolean seguridadHabilitada,
                            @Value("${seguridad.cliente-prueba:11111111-1111-1111-1111-111111111111}") UUID clientePrueba) {
        this.pedidoService = pedidoService;
        this.crearPedidoService = crearPedidoService;
        this.seguridadHabilitada = seguridadHabilitada;
        this.clientePrueba = clientePrueba;
    }

    /**
     * 1.1 Crear pedido. Token de servicio con scope ventas:pedidos:crear (lo exige SecurityConfig).
     * 201 si se registró; 200 con el mismo pedido si la Idempotency-Key ya se usó con el mismo cuerpo.
     */
    @PostMapping
    public ResponseEntity<CrearPedidoResponse> crear(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CrearPedidoRequest solicitud) {
        CrearPedidoService.Resultado r = crearPedidoService.crear(idempotencyKey, solicitud);
        if (!r.creado()) {
            return ResponseEntity.ok(r.pedido());
        }
        return ResponseEntity.created(URI.create("/api/v1/pedidos/" + r.pedido().pedidoId())).body(r.pedido());
    }

    @PostMapping("/{pedidoId}/pagos/notificacion")
    public ResponseEntity<Void> notificarPago() {
        throw new NoImplementadoException("1.2 Notificación de pago");
    }

    /**
     * 1.4b Listar mis pedidos (acuerdo H-03). Requiere token de usuario con rol CLIENTE
     * (lo exige SecurityConfig). La identidad sale del claim sub; no se acepta clienteId en la URL.
     */
    @GetMapping("/me")
    public ResponseEntity<MisPedidosResponse> misPedidos(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) EstadoPedido estado,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(pedidoService.listarMios(clienteDelToken(jwt), estado, page, pageSize));
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

    /** Con seguridad apagada (solo local) usa el cliente de prueba de Seguridad (kit §6). */
    private UUID clienteDelToken(Jwt jwt) {
        if (jwt != null) {
            return UUID.fromString(jwt.getSubject());
        }
        if (!seguridadHabilitada) {
            return clientePrueba;
        }
        throw new IllegalStateException("Petición autenticada sin JWT");
    }
}

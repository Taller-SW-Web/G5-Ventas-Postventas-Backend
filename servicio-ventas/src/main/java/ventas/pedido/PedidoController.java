package ventas.pedido;

import java.net.URI;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ventas.compartido.NoImplementadoException;
import ventas.pedido.dto.CambiarEstadoRequest;
import ventas.pedido.dto.CambiarEstadoResponse;
import ventas.pedido.dto.CrearPedidoRequest;
import ventas.pedido.dto.CrearPedidoResponse;
import ventas.pedido.dto.ListadoPedidosResponse;
import ventas.pedido.dto.MisPedidosResponse;
import ventas.pedido.dto.NotificacionPagoRequest;
import ventas.pedido.dto.NotificacionPagoResponse;
import ventas.pedido.dto.PedidoDetalle;

@RestController
@RequestMapping("/api/v1/pedidos")
public class PedidoController {

    private final PedidoService pedidoService;
    private final CrearPedidoService crearPedidoService;
    private final NotificacionPagoService notificacionPagoService;
    private final ConsultarPedidoService consultarPedidoService;
    private final CambiarEstadoService cambiarEstadoService;
    private final boolean seguridadHabilitada;
    private final UUID clientePrueba;

    public PedidoController(PedidoService pedidoService,
                            CrearPedidoService crearPedidoService,
                            NotificacionPagoService notificacionPagoService,
                            ConsultarPedidoService consultarPedidoService,
                            CambiarEstadoService cambiarEstadoService,
                            @Value("${seguridad.habilitada:true}") boolean seguridadHabilitada,
                            @Value("${seguridad.cliente-prueba:11111111-1111-1111-1111-111111111111}") UUID clientePrueba) {
        this.pedidoService = pedidoService;
        this.crearPedidoService = crearPedidoService;
        this.notificacionPagoService = notificacionPagoService;
        this.consultarPedidoService = consultarPedidoService;
        this.cambiarEstadoService = cambiarEstadoService;
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

    /**
     * 1.2 Notificación de pago. Token de servicio con scope ventas:pagos:webhook (pasarela o chatbot).
     * Siempre 200 si se aplicó o si es un reintento de la misma transacción.
     */
    @PostMapping("/{pedidoId}/pagos/notificacion")
    public ResponseEntity<NotificacionPagoResponse> notificarPago(
            @PathVariable String pedidoId,
            @Valid @RequestBody NotificacionPagoRequest notificacion) {
        return ResponseEntity.ok(notificacionPagoService.procesar(pedidoId, notificacion));
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

    /**
     * 1.3 Consultar pedido. Cliente (solo sus pedidos), Gestor o M2 con scope ventas:pedidos:consultar
     * (lo exige SecurityConfig). La titularidad del cliente se valida contra el claim sub.
     */
    @GetMapping("/{pedidoId}")
    public ResponseEntity<PedidoDetalle> consultar(@PathVariable String pedidoId,
                                                   @AuthenticationPrincipal Jwt jwt,
                                                   Authentication autenticacion) {
        return ResponseEntity.ok(consultarPedidoService.consultar(pedidoId, soloCliente(jwt, autenticacion)));
    }

    /** 1.4 Listar pedidos de un cliente (Gestor, rol ADMIN_VENTAS, lo exige SecurityConfig). */
    @GetMapping
    public ResponseEntity<ListadoPedidosResponse> listar(
            @RequestParam(required = false) UUID clienteId,
            @RequestParam(required = false) EstadoPedido estado,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "10") int tamano) {
        return ResponseEntity.ok(pedidoService.listar(clienteId, estado, desde, hasta, pagina, tamano));
    }

    /** 1.5 Cambiar estado. Despacho / Logística (scope ventas:pedidos:estado) o Gestor (lo exige SecurityConfig). */
    @PatchMapping("/{pedidoId}/estado")
    public ResponseEntity<CambiarEstadoResponse> cambiarEstado(@PathVariable String pedidoId,
                                                               @Valid @RequestBody CambiarEstadoRequest solicitud) {
        return ResponseEntity.ok(cambiarEstadoService.cambiar(pedidoId, solicitud));
    }

    @PostMapping("/{pedidoId}/eventos-logistica")
    public ResponseEntity<Void> eventoLogistica() {
        throw new NoImplementadoException("1.6 Eventos de logística");
    }

    /**
     * Solo un token de usuario CLIENTE (sin rol de Gestor) queda limitado a sus pedidos.
     * Gestor, token de servicio de M2 y seguridad apagada ven cualquier pedido.
     */
    static Optional<UUID> soloCliente(Jwt jwt, Authentication autenticacion) {
        if (jwt == null || autenticacion == null) {
            return Optional.empty();
        }
        boolean cliente = false;
        for (GrantedAuthority a : autenticacion.getAuthorities()) {
            switch (a.getAuthority()) {
                case "ROLE_ADMIN_VENTAS", "SCOPE_ventas:pedidos:consultar" -> { return Optional.empty(); }
                case "ROLE_CLIENTE" -> cliente = true;
                default -> { }
            }
        }
        return cliente ? Optional.of(UUID.fromString(jwt.getSubject())) : Optional.empty();
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

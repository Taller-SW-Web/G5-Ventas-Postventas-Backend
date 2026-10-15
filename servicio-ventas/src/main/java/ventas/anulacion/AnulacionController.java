package ventas.anulacion;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import ventas.anulacion.dto.*;
import ventas.pedido.ActorHistorial;

@RestController
@RequestMapping("/api/v1/pedidos")
@RequiredArgsConstructor
public class AnulacionController {

    private final AnulacionService anulacionService;

    /**
     * 1.8 Listar anulaciones pendientes de autorización (Gestor, rol ADMIN_VENTAS).
     * Ruta fija antes de las variables {pedidoId}.
     */
    @GetMapping("/anulaciones/pendientes")
    public ResponseEntity<ListadoAnulacionesPendientesResponse> listarPendientes(
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "10") int tamano) {
        return ResponseEntity.ok(anulacionService.listarPendientes(pagina, tamano));
    }

    /**
     * 1.7 Solicitar anulación de pedido (Token de canal o Gestor).
     */
    @PostMapping("/{pedidoId}/anulaciones")
    public ResponseEntity<SolicitarAnulacionResponse> solicitarAnulacion(
            @PathVariable String pedidoId,
            @Valid @RequestBody SolicitarAnulacionRequest req,
            @AuthenticationPrincipal Jwt jwt) {

        ActorHistorial actor = resolverActor(jwt, req.motivo());
        SolicitarAnulacionResponse resp = anulacionService.solicitarAnulacion(pedidoId, req, actor);

        if (Boolean.TRUE.equals(resp.autorizacionRequerida())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * 1.9 Aprobar o rechazar una anulación pendiente (Gestor, rol ADMIN_VENTAS).
     */
    @PatchMapping("/{pedidoId}/anulaciones/{anulacionId}")
    public ResponseEntity<ResolverAnulacionResponse> resolverAnulacion(
            @PathVariable String pedidoId,
            @PathVariable String anulacionId,
            @Valid @RequestBody ResolverAnulacionRequest req) {
        return ResponseEntity.ok(anulacionService.resolverAnulacion(pedidoId, anulacionId, req));
    }

    private static ActorHistorial resolverActor(Jwt jwt, String motivo) {
        if ("PAGO_NO_COMPLETADO".equalsIgnoreCase(motivo)) {
            return ActorHistorial.CANAL_CHATBOT;
        }
        if (jwt != null) {
            Object rolesClaim = jwt.getClaim("roles");
            if (rolesClaim != null && rolesClaim.toString().contains("ADMIN_VENTAS")) {
                return ActorHistorial.GESTOR;
            }
        }
        return ActorHistorial.CLIENTE;
    }
}

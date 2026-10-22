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
     * 1.8 Listar anulaciones (Gestor, rol ADMIN_VENTAS).
     * Soporta Server-Side Filtering, Sorting y Pagination delegados.
     */
    @GetMapping("/anulaciones")
    public ResponseEntity<ListadoAnulacionesPendientesResponse> listar(
            @RequestParam(required = false) EstadoAnulacion estado,
            @RequestParam(required = false) ventas.pedido.CanalVenta canal,
            @RequestParam(required = false) String busqueda,
            @RequestParam(value = "buscar", required = false) String buscar,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pagina", required = false) Integer pagina,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "tamano", required = false) Integer tamano,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "orden", required = false) String orden) {

        String busquedaEfectiva = busqueda != null ? busqueda : buscar;
        int pageEfectivo = page != null ? page : (pagina != null ? pagina : 0);
        int sizeEfectivo = size != null ? size : (tamano != null ? tamano : 10);
        String sortEfectivo = sort != null ? sort : (orden != null ? orden : "fecha,desc");

        return ResponseEntity.ok(anulacionService.listarConFiltros(
                estado, canal, busquedaEfectiva, pageEfectivo, sizeEfectivo, sortEfectivo
        ));
    }

    /**
     * 1.8 Retrocompatibilidad: Listar anulaciones pendientes de autorización.
     */
    @GetMapping("/anulaciones/pendientes")
    public ResponseEntity<ListadoAnulacionesPendientesResponse> listarPendientes(
            @RequestParam(required = false) EstadoAnulacion estado,
            @RequestParam(required = false) ventas.pedido.CanalVenta canal,
            @RequestParam(required = false) String busqueda,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pagina", required = false) Integer pagina,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "tamano", required = false) Integer tamano,
            @RequestParam(value = "sort", required = false) String sort) {

        EstadoAnulacion estadoEfectivo = (estado != null) ? estado : EstadoAnulacion.PENDIENTE_AUTORIZACION;
        int pageEfectivo = page != null ? page : (pagina != null ? pagina : 0);
        int sizeEfectivo = size != null ? size : (tamano != null ? tamano : 10);
        String sortEfectivo = sort != null ? sort : "fecha,desc";

        return ResponseEntity.ok(anulacionService.listarConFiltros(
                estadoEfectivo, canal, busqueda, pageEfectivo, sizeEfectivo, sortEfectivo
        ));
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

package ventas.pedido;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import ventas.pedido.dto.EvidenciaCuponResponse;

/**
 * Evidencia de cupón para Promotions de Productos (G6, contrato H5). Token de servicio de promotions-svc con scope
 * ventas:pedidos:evidencia-cupon:leer y aud api-ventas (lo exige SecurityConfig). Otro cliente, aunque traiga el
 * scope, recibe 403. Respuesta sin caché.
 */
@RestController
@RequiredArgsConstructor
public class EvidenciaCuponController {

    static final String CLIENTE_PROMOCIONES = "promotions-svc";

    private final EvidenciaCuponService evidencias;

    @GetMapping("/api/v1/integraciones/productos/pedidos/{orderId}/evidencia-cupon")
    public ResponseEntity<EvidenciaCuponResponse> evidencia(@PathVariable String orderId,
                                                            @AuthenticationPrincipal Jwt jwt) {
        if (jwt != null && !CLIENTE_PROMOCIONES.equals(jwt.getSubject())) {
            throw new AccessDeniedException("Solo promotions-svc puede leer la evidencia de cupón");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evidencias.evidencia(orderId));
    }
}

package ventas.pedido;

import java.net.URI;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ventas.pedido.dto.CrearReemplazoRequest;
import ventas.pedido.dto.CrearReemplazoResponse;

/** 1.11: solo M2 (modulo-postventa) con el scope ventas:pedidos:reemplazar. */
@RestController
@RequestMapping("/api/v1/pedidos/reemplazos")
@RequiredArgsConstructor
public class ReemplazoController {

    private final ReemplazoService reemplazos;

    @PostMapping
    public ResponseEntity<CrearReemplazoResponse> crear(@Valid @RequestBody CrearReemplazoRequest req) {
        ReemplazoService.Resultado r = reemplazos.crear(req);
        if (!r.creado()) {
            return ResponseEntity.ok(r.reemplazo());
        }
        return ResponseEntity.created(URI.create("/api/v1/pedidos/" + r.reemplazo().pedidoId())).body(r.reemplazo());
    }
}

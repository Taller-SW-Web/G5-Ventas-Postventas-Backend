package postventa.reembolso;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import postventa.reembolso.dto.*;

@RestController
@RequestMapping("/api/v2/reembolsos")
public class ReembolsoController {
    private final ReembolsoService servicio;
    public ReembolsoController(ReembolsoService servicio) { this.servicio=servicio; }
    @PostMapping
    public ResponseEntity<ProcesarReembolsoResponse> procesar(@RequestHeader("X-Idempotency-Key") String clave, @Valid @RequestBody ProcesarReembolsoRequest solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.procesar(clave, solicitud));
    }
}

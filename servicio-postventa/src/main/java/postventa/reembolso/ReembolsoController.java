package postventa.reembolso;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import postventa.reembolso.dto.*;

@RestController
@RequestMapping("/api/v2/reembolsos")
public class ReembolsoController {
    private final ReembolsoService servicio;
    private final ConsultaReembolsosService consulta;
    private final boolean seguridadHabilitada;
    public ReembolsoController(ReembolsoService servicio, ConsultaReembolsosService consulta,
                               @Value("${seguridad.habilitada:true}") boolean seguridadHabilitada) {
        this.servicio=servicio;
        this.consulta=consulta;
        this.seguridadHabilitada=seguridadHabilitada;
    }
    /** Bandeja del Gestor (mockup 08, rol ADMIN_VENTAS). Filtros opcionales: estado, origen y pedidoId. */
    @GetMapping
    public ResponseEntity<ListadoReembolsosResponse> listar(@RequestParam(required = false) EstadoReembolso estado,
            @RequestParam(required = false) OrigenReembolso origen,
            @RequestParam(required = false) String pedidoId,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "10") int tamano) {
        return ResponseEntity.ok(consulta.listar(estado, origen, pedidoId, pagina, tamano));
    }
    @PostMapping
    public ResponseEntity<ProcesarReembolsoResponse> procesar(@RequestHeader("X-Idempotency-Key") String clave,
            @Valid @RequestBody ProcesarReembolsoRequest solicitud, Authentication autenticacion) {
        validarQuienPuedeSolicitar(solicitud, autenticacion);
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.procesar(clave, solicitud));
    }
    private void validarQuienPuedeSolicitar(ProcesarReembolsoRequest solicitud, Authentication autenticacion) {
        if (!seguridadHabilitada || solicitud.origen() != OrigenReembolso.ANULACION) return;
        boolean esM1 = autenticacion != null && autenticacion.getAuthorities().stream()
                .anyMatch(a -> "SCOPE_postventa:reembolsos:procesar".equals(a.getAuthority()));
        if (!esM1) throw new AccessDeniedException("Solo M1 puede solicitar el reembolso de una anulación");
    }
}

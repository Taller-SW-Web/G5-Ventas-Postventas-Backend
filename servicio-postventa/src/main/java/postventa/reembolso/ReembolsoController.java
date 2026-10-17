package postventa.reembolso;

import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
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
    /** Bandeja del Gestor (mockup 08). Cliente es solo del detalle para evitar una llamada a M1 por fila. */
    @GetMapping
    public ResponseEntity<ListadoReembolsosResponse> listar(@RequestParam(required = false) EstadoReembolso estado,
            @RequestParam(required = false) OrigenReembolso origen,
            @RequestParam(required = false) String pedidoId,
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) ConsultaReembolsosService.Orden orden,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "10") int tamano) {
        return ResponseEntity.ok(consulta.listar(estado, origen, pedidoId, buscar, desde, hasta, orden, pagina, tamano));
    }
    @GetMapping("/{reembolsoId}")
    public ResponseEntity<DetalleReembolsoResponse> detalle(@PathVariable String reembolsoId) {
        return ResponseEntity.ok(consulta.detalle(reembolsoId));
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

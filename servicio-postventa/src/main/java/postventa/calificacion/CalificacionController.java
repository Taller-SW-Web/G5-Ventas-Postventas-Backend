package postventa.calificacion;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import postventa.calificacion.dto.ListadoCalificacionesResponse;
import postventa.calificacion.dto.RegistrarCsatRequest;
import postventa.compartido.CanalVenta;

/**
 * Endpoint de registro de encuestas CSAT.
 */
@RestController
@RequestMapping("/api/v2/csat")
public class CalificacionController {

    private final CalificacionService servicio;
    private final ConsultaCalificacionesService consulta;
    private final boolean seguridadHabilitada;
    private final UUID clientePrueba;

    public CalificacionController(
            CalificacionService servicio,
            ConsultaCalificacionesService consulta,
            @Value("${seguridad.habilitada:true}")
            boolean seguridadHabilitada,
            @Value("${seguridad.cliente-prueba:11111111-1111-1111-1111-111111111111}")
            UUID clientePrueba) {

        this.servicio = servicio;
        this.consulta = consulta;
        this.seguridadHabilitada = seguridadHabilitada;
        this.clientePrueba = clientePrueba;
    }

    @PostMapping
    public ResponseEntity<Void> registrar(
            @Valid @RequestBody RegistrarCsatRequest solicitud,
            @AuthenticationPrincipal Jwt jwt) {

        UUID clienteAutenticado = obtenerCliente(jwt);

        servicio.registrar(solicitud, clienteAutenticado);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** Calificaciones para el Gestor (mockup 09, rol ADMIN_VENTAS). Filtros opcionales. */
    @GetMapping
    public ResponseEntity<ListadoCalificacionesResponse> listar(
            @RequestParam(required = false) CanalVenta canal,
            @RequestParam(required = false) Integer puntaje,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "10") int tamano) {

        return ResponseEntity.ok(consulta.listar(canal, puntaje, desde, hasta, pagina, tamano));
    }

    private UUID obtenerCliente(Jwt jwt) {
        if (!seguridadHabilitada) {
            return clientePrueba;
        }

        if (jwt == null) {
            throw new AccessDeniedException(
                    "No se encontró la identidad del cliente autenticado");
        }

        return UUID.fromString(jwt.getSubject());
    }
}

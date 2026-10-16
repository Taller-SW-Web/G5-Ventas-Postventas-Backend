package postventa.calificacion;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import postventa.calificacion.dto.RegistrarCsatRequest;

/**
 * Endpoint de registro de encuestas CSAT.
 */
@RestController
@RequestMapping("/api/v2/csat")
public class CalificacionController {

    private final CalificacionService servicio;
    private final boolean seguridadHabilitada;
    private final UUID clientePrueba;

    public CalificacionController(
            CalificacionService servicio,
            @Value("${seguridad.habilitada:true}")
            boolean seguridadHabilitada,
            @Value("${seguridad.cliente-prueba:11111111-1111-1111-1111-111111111111}")
            UUID clientePrueba) {

        this.servicio = servicio;
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

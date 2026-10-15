package postventa.reclamo;

import java.net.URI;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import postventa.compartido.ErrorResponse;
import postventa.devolucion.Solicitante;
import postventa.reclamo.dto.ListadoReclamosResponse;
import postventa.reclamo.dto.ReclamoDetalle;
import postventa.reclamo.dto.RegistrarReclamoRequest;
import postventa.reclamo.dto.RegistrarReclamoResponse;
import postventa.reclamo.dto.RespuestaReclamoResponse;
import postventa.reclamo.dto.ResponderReclamoRequest;

/** F6: endpoints 2.8 a 2.11 y 2.10b de api-postventas.md. Quién puede llamar cada uno lo decide SecurityConfig. */
@RestController
@RequestMapping("/api/v2/reclamos")
public class ReclamoController {

    private final ReclamoService reclamoService;
    private final boolean seguridadHabilitada;
    private final UUID clientePrueba;

    public ReclamoController(ReclamoService reclamoService,
                             @Value("${seguridad.habilitada:true}") boolean seguridadHabilitada,
                             @Value("${seguridad.cliente-prueba:11111111-1111-1111-1111-111111111111}") UUID clientePrueba) {
        this.reclamoService = reclamoService;
        this.seguridadHabilitada = seguridadHabilitada;
        this.clientePrueba = clientePrueba;
    }

    /** 2.8 Registrar. Canal (scope postventa:reclamos:escribir) o el propio cliente. */
    @PostMapping
    public ResponseEntity<RegistrarReclamoResponse> registrar(@Valid @RequestBody RegistrarReclamoRequest solicitud,
                                                              Authentication auth) {
        RegistrarReclamoResponse r = reclamoService.registrar(solicitud, solicitante(auth));
        return ResponseEntity.created(URI.create("/api/v2/reclamos/" + r.codigoSeguimiento())).body(r);
    }

    /** 2.10b Mis reclamos. La identidad sale del sub del token, nunca de la URL. */
    @GetMapping("/me")
    public ResponseEntity<ListadoReclamosResponse> misReclamos(
            Authentication auth,
            @RequestParam(required = false) EstadoReclamo estado,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "10") int tamano) {
        UUID cliente = seguridadHabilitada ? solicitante(auth).id() : clientePrueba;
        return ResponseEntity.ok(reclamoService.listar(FiltroReclamos.de(null, cliente, estado), pagina, tamano));
    }

    /** 2.9 Consultar por código de seguimiento. Su titular o el Gestor. */
    @GetMapping("/{codigoSeguimiento}")
    public ResponseEntity<ReclamoDetalle> consultar(@PathVariable String codigoSeguimiento, Authentication auth) {
        return ResponseEntity.ok(reclamoService.consultar(codigoSeguimiento, solicitante(auth)));
    }

    /**
     * 2.10 Listado administrativo (rol ADMIN_VENTAS). Sin filtros lista todos los reclamos.
     * buscar, sla, desde, hasta y orden van además del contrato (bandeja del mockup 07).
     */
    @GetMapping
    public ResponseEntity<ListadoReclamosResponse> listar(
            @RequestParam(required = false) String documento,
            @RequestParam(required = false) UUID clienteId,
            @RequestParam(required = false) EstadoReclamo estado,
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false) EstadoSla sla,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) FiltroReclamos.Orden orden,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "10") int tamano) {
        return ResponseEntity.ok(reclamoService.listar(
                new FiltroReclamos(documento, clienteId, estado, buscar, sla, desde, hasta, orden), pagina, tamano));
    }

    /** 2.11 Responder o iniciar la atención (rol ADMIN_VENTAS). */
    @PatchMapping("/{reclamoId}/respuesta")
    public ResponseEntity<RespuestaReclamoResponse> responder(@PathVariable String reclamoId,
                                                              @Valid @RequestBody ResponderReclamoRequest solicitud) {
        return ResponseEntity.ok(reclamoService.responder(reclamoId, solicitud));
    }

    /** pagina o tamano que no son números responden PAGINACION_INVALIDA, igual que en F3. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> parametroInvalido(MethodArgumentTypeMismatchException e) {
        if ("pagina".equals(e.getName()) || "tamano".equals(e.getName())) {
            return ResponseEntity.badRequest().body(ErrorResponse.de("PAGINACION_INVALIDA",
                    "pagina debe ser 0 o más y tamano entre 1 y " + ReclamoService.TAMANO_MAXIMO_PAGINA));
        }
        return ResponseEntity.badRequest().body(ErrorResponse.de("FORMATO_INVALIDO",
                "El cuerpo o un parámetro no tiene el formato esperado"));
    }

    /** Rol por el token (acuerdo A10), igual que DevolucionController. Con la seguridad apagada actúa como Gestor. */
    private Solicitante solicitante(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken token) {
            Set<String> autoridades = token.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
            if (autoridades.contains("ROLE_ADMIN_VENTAS")) {
                return Solicitante.gestor(UUID.fromString(token.getToken().getSubject()));
            }
            if (autoridades.contains("ROLE_CLIENTE")) {
                return Solicitante.cliente(UUID.fromString(token.getToken().getSubject()));
            }
            return Solicitante.servicio();
        }
        if (!seguridadHabilitada) {
            return Solicitante.gestor(null);
        }
        throw new IllegalStateException("Petición autenticada sin JWT");
    }
}

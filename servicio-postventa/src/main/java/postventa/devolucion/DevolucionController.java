package postventa.devolucion;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartFile;

import postventa.compartido.ErrorResponse;
import postventa.devolucion.dto.ConfirmarRecepcionRequest;
import postventa.devolucion.dto.ConfirmarRecepcionResponse;
import postventa.devolucion.dto.DevolucionDetalle;
import postventa.devolucion.dto.EvidenciaSubidaResponse;
import postventa.devolucion.dto.ListadoDevolucionesResponse;
import postventa.devolucion.dto.RegistrarDevolucionRequest;
import postventa.devolucion.dto.RegistrarDevolucionResponse;
import postventa.devolucion.dto.ResolucionResponse;
import postventa.devolucion.dto.ResolverDevolucionRequest;

/** F3: endpoints 2.1 a 2.5b de api-postventas.md. Quién puede llamar cada uno lo decide SecurityConfig. */
@RestController
@RequestMapping("/api/v2/devoluciones")
public class DevolucionController {

    private final DevolucionService devolucionService;
    private final EvidenciaService evidenciaService;
    private final RecepcionService recepcionService;
    private final boolean seguridadHabilitada;
    private final UUID clientePrueba;

    public DevolucionController(DevolucionService devolucionService,
                                EvidenciaService evidenciaService,
                                RecepcionService recepcionService,
                                @Value("${seguridad.habilitada:true}") boolean seguridadHabilitada,
                                @Value("${seguridad.cliente-prueba:11111111-1111-1111-1111-111111111111}") UUID clientePrueba) {
        this.devolucionService = devolucionService;
        this.evidenciaService = evidenciaService;
        this.recepcionService = recepcionService;
        this.seguridadHabilitada = seguridadHabilitada;
        this.clientePrueba = clientePrueba;
    }

    /** 2.1 Subir evidencia (multipart, campo file). */
    @PostMapping(path = "/evidencias/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EvidenciaSubidaResponse> subirEvidencia(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(201).body(evidenciaService.subir(file));
    }

    /** 2.2 Registrar devolución o cambio. Canal (scope postventa:devoluciones:escribir) o el propio cliente. */
    @PostMapping
    public ResponseEntity<RegistrarDevolucionResponse> registrar(@Valid @RequestBody RegistrarDevolucionRequest solicitud,
                                                                 Authentication auth) {
        RegistrarDevolucionResponse r = devolucionService.registrar(solicitud, solicitante(auth));
        return ResponseEntity.created(URI.create("/api/v2/devoluciones/" + r.devolucionId())).body(r);
    }

    /** 2.4b Mis devoluciones. Token del cliente; la identidad sale del sub, nunca de la URL. */
    @GetMapping("/me")
    public ResponseEntity<ListadoDevolucionesResponse> misDevoluciones(
            Authentication auth,
            @RequestParam(required = false) EstadoDevolucion estado,
            @RequestParam(required = false) TipoDevolucion tipo,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "10") int tamano) {
        UUID cliente = seguridadHabilitada ? solicitante(auth).id() : clientePrueba;
        return ResponseEntity.ok(devolucionService.listar(cliente, estado, tipo, pagina, tamano).sinCliente());
    }

    /** 2.3 Consultar un expediente. Su titular o el Gestor (al abrirlo, pasa a EN_EVALUACION). */
    @GetMapping("/{devolucionId}")
    public ResponseEntity<DevolucionDetalle> consultar(@PathVariable String devolucionId, Authentication auth) {
        return ResponseEntity.ok(devolucionService.consultar(devolucionId, solicitante(auth)));
    }

    /** 2.4 Listado administrativo. Rol ADMIN_VENTAS. Sin clienteId lista todos. */
    @GetMapping
    public ResponseEntity<ListadoDevolucionesResponse> listar(
            @RequestParam(required = false) UUID clienteId,
            @RequestParam(required = false) EstadoDevolucion estado,
            @RequestParam(required = false) TipoDevolucion tipo,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "10") int tamano) {
        return ResponseEntity.ok(devolucionService.listar(clienteId, estado, tipo, pagina, tamano));
    }

    /** 2.5 Resolver. Rol ADMIN_VENTAS. */
    @PatchMapping("/{devolucionId}/resolucion")
    public ResponseEntity<ResolucionResponse> resolver(@PathVariable String devolucionId,
                                                       @Valid @RequestBody ResolverDevolucionRequest solicitud,
                                                       Authentication auth) {
        return ResponseEntity.ok(devolucionService.resolver(devolucionId, solicitud, solicitante(auth)));
    }

    /** 2.5b Confirmar recepción física: recién aquí se reintegra stock. Rol ADMIN_VENTAS (Gestor o almacén). */
    @PatchMapping("/{devolucionId}/recepcion")
    public ResponseEntity<ConfirmarRecepcionResponse> confirmarRecepcion(@PathVariable String devolucionId,
                                                                         @Valid @RequestBody ConfirmarRecepcionRequest solicitud,
                                                                         Authentication auth) {
        return ResponseEntity.ok(recepcionService.confirmar(devolucionId, solicitud, solicitante(auth)));
    }

    /**
     * 2.4 y 2.4b: pagina o tamano que no son números responden PAGINACION_INVALIDA (contrato v1.5.0),
     * igual que si están fuera de rango. Cualquier otro parámetro mal formado sigue siendo FORMATO_INVALIDO.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> parametroInvalido(MethodArgumentTypeMismatchException e) {
        if ("pagina".equals(e.getName()) || "tamano".equals(e.getName())) {
            return ResponseEntity.badRequest().body(ErrorResponse.de("PAGINACION_INVALIDA",
                    "pagina debe ser 0 o más y tamano entre 1 y " + DevolucionService.TAMANO_MAXIMO_PAGINA));
        }
        return ResponseEntity.badRequest().body(ErrorResponse.de("FORMATO_INVALIDO",
                "El cuerpo o un parámetro no tiene el formato esperado"));
    }

    /**
     * Rol por el token (acuerdo A10). Si trae ADMIN_VENTAS manda sobre CLIENTE.
     * Con la seguridad apagada (solo local) actúa como Gestor, sin id.
     */
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
            return Solicitante.servicio(token.getToken().getSubject());
        }
        if (!seguridadHabilitada) {
            return Solicitante.gestor(null);
        }
        throw new IllegalStateException("Petición autenticada sin JWT");
    }
}

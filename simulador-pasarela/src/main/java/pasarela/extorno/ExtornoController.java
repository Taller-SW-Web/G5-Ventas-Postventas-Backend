package pasarela.extorno;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pasarela.extorno.dto.ExtornoRequest;
import pasarela.extorno.dto.ExtornoResponse;

/**
 * Endpoint externo de extornos para F4.
 */
@RestController
@RequestMapping("/pasarela/v1/extornos")
public class ExtornoController {

    private final ExtornoService servicio;

    public ExtornoController(ExtornoService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<ExtornoResponse> procesar(
            @RequestHeader(
                    name = "X-Idempotency-Key",
                    required = false)
            String clave,
            @Valid @RequestBody ExtornoRequest solicitud) {

        ExtornoResponse respuesta = servicio.procesar(clave, solicitud);

        return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);
    }
}

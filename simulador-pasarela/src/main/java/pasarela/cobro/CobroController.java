package pasarela.cobro;

import java.util.Objects;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import pasarela.cobro.dto.CobroRequest;
import pasarela.cobro.dto.CobroResponse;
import pasarela.cobro.dto.ConsultaCobroResponse;

/**
 * Permite iniciar cobros y consultar su procesamiento.
 */
@RestController
@RequestMapping("/pasarela/v1")
public class CobroController {

    private final CobroService servicio;

    public CobroController(CobroService servicio) {
        this.servicio = Objects.requireNonNull(servicio);
    }

    /**
     * Registra la transacción y responde sin esperar el resultado del cobro.
     */
    @PostMapping("/cobros")
    @ResponseStatus(HttpStatus.CREATED)
    public CobroResponse iniciar(
            @Valid @RequestBody CobroRequest solicitud) {

        return servicio.iniciar(solicitud);
    }

    /**
     * Consulta el resultado y si M1 recibió la notificación.
     */
    @GetMapping("/transacciones/{transaccionId}")
    public ConsultaCobroResponse consultar(
            @PathVariable("transaccionId") String transaccionId) {

        return servicio.consultar(transaccionId)
                .orElseThrow(CobroNoEncontradoException::new);
    }
}

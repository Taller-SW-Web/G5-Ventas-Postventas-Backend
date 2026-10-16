package postventa.integracion.eventos;

import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 2.13 Recibir evento de pedido. Lo llama M1 (EnvioEventosScheduler) con su credencial de servicio;
 * no pasa por el Gateway. La validación por tipo la hace EventoPedidoService (400 si no cumple).
 */
@RestController
@RequestMapping("/api/v2/eventos")
public class EventoPedidoController {

    private final EventoPedidoService servicio;

    public EventoPedidoController(EventoPedidoService servicio) {
        this.servicio = Objects.requireNonNull(servicio);
    }

    /** 200 tanto si el evento es nuevo como si ya se había procesado (repetido = true): M1 deja de reintentar. */
    @PostMapping
    public ResponseEntity<Respuesta> recibir(@RequestBody EventoPedidoPayload evento) {
        boolean nuevo = servicio.recibir(evento);
        return ResponseEntity.ok(new Respuesta(evento.idEvento().toString(), !nuevo));
    }

    public record Respuesta(String idEvento, boolean repetido) {}
}

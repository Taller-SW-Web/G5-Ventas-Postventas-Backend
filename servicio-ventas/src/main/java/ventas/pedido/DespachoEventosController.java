package ventas.pedido;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ventas.pedido.dto.EventoDespachoRequest;
import ventas.pedido.dto.EventoDespachoResponse;

/**
 * 1.5 Webhook de eventos de Despacho (acuerdo G2, D2 y D6). Token de servicio de modulo-despacho con scope
 * ventas:pedidos:estado (D7, lo exige SecurityConfig). Despacho NO envía Idempotency-Key en el webhook (G2, 18/11):
 * la idempotencia es por idEvento. Si llega la cabecera, se ignora.
 */
@RestController
@RequiredArgsConstructor
public class DespachoEventosController {

    private final EventoDespachoService eventos;

    @PostMapping("/api/v1/integraciones/despachos/eventos")
    public ResponseEntity<EventoDespachoResponse> recibir(
            @Valid @RequestBody EventoDespachoRequest evento) {
        return ResponseEntity.ok(eventos.procesar(evento));
    }
}

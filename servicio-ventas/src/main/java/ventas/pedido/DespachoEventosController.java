package ventas.pedido;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import ventas.pedido.dto.EventoDespachoRequest;
import ventas.pedido.dto.EventoDespachoResponse;

/**
 * 1.5 Webhook de eventos de Despacho (acuerdo G2, D2 y D6). Token de servicio de modulo-despacho con scope
 * ventas:pedidos:estado (D7, lo exige SecurityConfig) y cabecera Idempotency-Key UUID v4 (D9).
 */
@RestController
@RequiredArgsConstructor
public class DespachoEventosController {

    private final ValidadorPedido validador;
    private final EventoDespachoService eventos;

    @PostMapping("/api/v1/integraciones/despachos/eventos")
    public ResponseEntity<EventoDespachoResponse> recibir(
            @RequestHeader(value = "Idempotency-Key", required = false) String clave,
            @Valid @RequestBody EventoDespachoRequest evento) {
        validador.validarClave(clave);
        return ResponseEntity.ok(eventos.procesar(evento));
    }
}

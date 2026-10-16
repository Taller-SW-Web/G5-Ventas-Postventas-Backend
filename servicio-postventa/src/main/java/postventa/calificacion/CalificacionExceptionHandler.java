package postventa.calificacion;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import postventa.compartido.ErrorResponse;

/**
 * Maneja el error específico de pedido no entregado para F5.
 */
@RestControllerAdvice(basePackages = "postventa.calificacion")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CalificacionExceptionHandler {

    @ExceptionHandler(PedidoNoEntregadoException.class)
    public ResponseEntity<ErrorResponse> pedidoNoEntregado(
            PedidoNoEntregadoException error) {

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.de(
                        "ORDER_NOT_DELIVERED",
                        error.getMessage()));
    }
}

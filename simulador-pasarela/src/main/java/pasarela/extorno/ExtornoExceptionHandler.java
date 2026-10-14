package pasarela.extorno;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import pasarela.extorno.dto.ExtornoError;

/**
 * Traduce los errores de extornos a respuestas HTTP.
 */
@RestControllerAdvice(basePackages = "pasarela.extorno")
public class ExtornoExceptionHandler {

    @ExceptionHandler(ExtornoException.class)
    public ResponseEntity<ExtornoError> errorDeExtorno(
            ExtornoException error) {

        return responder(
                error.getEstado(),
                error.getCodigo(),
                error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ExtornoError> datosInvalidos(
            MethodArgumentNotValidException error) {

        return responder(
                HttpStatus.BAD_REQUEST,
                "DATOS_INVALIDOS",
                "Revise referencia, monto positivo y moneda PEN o USD");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ExtornoError> formatoInvalido(
            HttpMessageNotReadableException error) {

        return responder(
                HttpStatus.BAD_REQUEST,
                "FORMATO_INVALIDO",
                "El cuerpo JSON o el escenario no tiene un formato válido");
    }

    private ResponseEntity<ExtornoError> responder(
            HttpStatus estado,
            String codigo,
            String mensaje) {

        ExtornoError respuesta = new ExtornoError(
                codigo,
                mensaje,
                OffsetDateTime.now(ZoneOffset.UTC));

        return ResponseEntity.status(Objects.requireNonNull(estado))
                .body(respuesta);
    }
}

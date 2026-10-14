package pasarela.cobro;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import pasarela.cobro.dto.CobroError;

/**
 * Traduce los errores del controlador de cobros a respuestas HTTP.
 */
@RestControllerAdvice(basePackageClasses = CobroController.class)
public class CobroExceptionHandler {

    @ExceptionHandler(CobroNoEncontradoException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public CobroError transaccionNoEncontrada(
            CobroNoEncontradoException error) {

        return respuesta(
                "TRANSACCION_NO_ENCONTRADA",
                error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CobroError datosInvalidos() {
        return respuesta(
                "DATOS_INVALIDOS",
                "Revisa los campos obligatorios, el monto, "
                        + "la moneda y el método de pago");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CobroError formatoInvalido() {
        return respuesta(
                "FORMATO_INVALIDO",
                "El cuerpo de la solicitud tiene un formato "
                        + "o un valor no permitido");
    }

    private static CobroError respuesta(String codigo, String mensaje) {
        return new CobroError(
                codigo,
                mensaje,
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}

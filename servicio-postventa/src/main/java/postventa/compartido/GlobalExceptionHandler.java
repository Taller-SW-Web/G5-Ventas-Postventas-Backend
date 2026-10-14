package postventa.compartido;

import java.util.List;

import com.fasterxml.jackson.databind.JsonMappingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Convierte toda excepción al formato de error del contrato. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SolicitudInvalidaException.class)
    public ResponseEntity<ErrorResponse> solicitudInvalida(SolicitudInvalidaException e) {
        return responder(HttpStatus.BAD_REQUEST, ErrorResponse.de(e.getCodigo(), e.getMessage(), e.getDetalles()));
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ErrorResponse> noEncontrado(RecursoNoEncontradoException e) {
        return responder(HttpStatus.NOT_FOUND, ErrorResponse.de(e.getCodigo(), e.getMessage()));
    }

    @ExceptionHandler(ConflictoException.class)
    public ResponseEntity<ErrorResponse> conflicto(ConflictoException e) {
        return responder(HttpStatus.CONFLICT, ErrorResponse.de(e.getCodigo(), e.getMessage()));
    }

    /** Una carrera contra una restricción única no debe exponer un 500 al cliente. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> conflictoDeIntegridad(DataIntegrityViolationException e) {
        return responder(HttpStatus.CONFLICT,
                ErrorResponse.de("CONFLICTO_DE_CONCURRENCIA", "La operación fue registrada por otra solicitud"));
    }

    @ExceptionHandler(DependenciaNoDisponibleException.class)
    public ResponseEntity<ErrorResponse> dependenciaNoDisponible(DependenciaNoDisponibleException e) {
        log.warn("Dependencia no disponible: {}", e.getMessage(), e.getCause());
        return responder(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.de(e.getCodigo(), e.getMessage()));
    }

    @ExceptionHandler(NoImplementadoException.class)
    public ResponseEntity<ErrorResponse> noImplementado(NoImplementadoException e) {
        return responder(HttpStatus.NOT_IMPLEMENTED, ErrorResponse.de("NO_IMPLEMENTADO", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> datosInvalidos(MethodArgumentNotValidException e) {
        List<ErrorResponse.Detalle> detalles = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorResponse.Detalle(f.getField(), f.getDefaultMessage()))
                .toList();
        return responder(HttpStatus.BAD_REQUEST,
                ErrorResponse.de("DATOS_INVALIDOS", "La solicitud tiene campos inválidos", detalles));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> cuerpoNoLegible(HttpMessageNotReadableException e) {
        if (esOrigenNoPermitido(e)) {
            return responder(HttpStatus.FORBIDDEN,
                    ErrorResponse.de("ORIGEN_NO_PERMITIDO", "El origen debe ser ANULACION o DEVOLUCION"));
        }
        return responder(HttpStatus.BAD_REQUEST,
                ErrorResponse.de("FORMATO_INVALIDO", "El cuerpo o un parámetro no tiene el formato esperado"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> parametroFormatoInvalido(MethodArgumentTypeMismatchException e) {
        return responder(HttpStatus.BAD_REQUEST,
                ErrorResponse.de("FORMATO_INVALIDO", "El cuerpo o un parámetro no tiene el formato esperado"));
    }

    private static boolean esOrigenNoPermitido(HttpMessageNotReadableException e) {
        Throwable causa = e.getCause();
        if (!(causa instanceof JsonMappingException json)) return false;
        return json.getPath().stream().anyMatch(referencia -> "origen".equals(referencia.getFieldName()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> cabeceraFaltante(MissingRequestHeaderException e) {
        return responder(HttpStatus.BAD_REQUEST,
                ErrorResponse.de("CABECERA_FALTANTE", "Falta la cabecera " + e.getHeaderName()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> parametroFaltante(Exception e) {
        String nombre = e instanceof MissingServletRequestParameterException p ? p.getParameterName()
                : ((MissingServletRequestPartException) e).getRequestPartName();
        return responder(HttpStatus.BAD_REQUEST,
                ErrorResponse.de("PARAMETRO_FALTANTE", "Falta el parámetro " + nombre));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> archivoMuyGrande(MaxUploadSizeExceededException e) {
        return responder(HttpStatus.BAD_REQUEST,
                ErrorResponse.de("ARCHIVO_INVALIDO", "El archivo supera el tamaño máximo de 5 MB"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> accesoDenegado(AccessDeniedException e) {
        return responder(HttpStatus.FORBIDDEN,
                ErrorResponse.de("ACCESO_DENEGADO", "No tiene permiso para esta operación"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> rutaInexistente(NoResourceFoundException e) {
        return responder(HttpStatus.NOT_FOUND, ErrorResponse.de("RUTA_NO_ENCONTRADA", "La ruta no existe"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> metodoNoPermitido(HttpRequestMethodNotSupportedException e) {
        return responder(HttpStatus.METHOD_NOT_ALLOWED,
                ErrorResponse.de("METODO_NO_PERMITIDO", "Método " + e.getMethod() + " no permitido"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> errorInterno(Exception e) {
        log.error("Error no controlado", e);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorResponse.de("ERROR_INTERNO", "Ocurrió un error interno"));
    }

    private static ResponseEntity<ErrorResponse> responder(HttpStatus estado, ErrorResponse cuerpo) {
        return ResponseEntity.status(estado).body(cuerpo);
    }
}

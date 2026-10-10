package postventa.compartido;

import java.util.List;

/** 400: dato inválido o regla de formato. */
public class SolicitudInvalidaException extends RuntimeException {

    private final String codigo;
    private final List<ErrorResponse.Detalle> detalles;

    public SolicitudInvalidaException(String codigo, String mensaje) {
        this(codigo, mensaje, List.of());
    }

    public SolicitudInvalidaException(String codigo, String mensaje, List<ErrorResponse.Detalle> detalles) {
        super(mensaje);
        this.codigo = codigo;
        this.detalles = detalles == null ? List.of() : List.copyOf(detalles);
    }

    public String getCodigo() { return codigo; }

    public List<ErrorResponse.Detalle> getDetalles() { return detalles; }
}

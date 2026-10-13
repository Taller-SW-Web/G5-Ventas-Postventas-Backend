package ventas.compartido;

/**
 * 503: otro sistema (Productos, M2, pasarela) no respondió o respondió con un error
 * que no es del negocio. La operación no se dio por hecha y se puede reintentar.
 */
public class DependenciaNoDisponibleException extends RuntimeException {

    private final String codigo;

    public DependenciaNoDisponibleException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public DependenciaNoDisponibleException(String codigo, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.codigo = codigo;
    }

    public String getCodigo() { return codigo; }
}

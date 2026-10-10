package postventa.compartido;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Formato de error del contrato: codigo, mensaje, timestamp y detalles. */
public record ErrorResponse(String codigo, String mensaje, OffsetDateTime timestamp, List<Detalle> detalles) {

    public record Detalle(String campo, String error) {}

    public static ErrorResponse de(String codigo, String mensaje) {
        return de(codigo, mensaje, List.of());
    }

    public static ErrorResponse de(String codigo, String mensaje, List<Detalle> detalles) {
        return new ErrorResponse(codigo, mensaje,
                OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS),
                detalles == null ? List.of() : List.copyOf(detalles));
    }
}

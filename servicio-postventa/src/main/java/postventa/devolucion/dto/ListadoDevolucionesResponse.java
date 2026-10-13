package postventa.devolucion.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import postventa.devolucion.EstadoDevolucion;
import postventa.devolucion.MotivoDevolucion;
import postventa.devolucion.TipoDevolucion;

/** Respuesta de 2.4 y 2.4b. En /me no va clienteId en la raíz (ya está en el token). */
public record ListadoDevolucionesResponse(
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID clienteId,
        List<Resumen> contenido, int pagina, int totalPaginas, long totalElementos) {

    /** estadoReembolso solo viene cuando la resolución fue un reembolso. */
    public record Resumen(String devolucionId, String pedidoId, TipoDevolucion tipo, EstadoDevolucion estado,
                          MotivoDevolucion motivo, String estadoReembolso, OffsetDateTime fechaRegistro) {}

    public ListadoDevolucionesResponse sinCliente() {
        return new ListadoDevolucionesResponse(null, contenido, pagina, totalPaginas, totalElementos);
    }
}

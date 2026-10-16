package postventa.reclamo.dto;

import java.time.OffsetDateTime;
import java.util.List;

import postventa.compartido.CanalVenta;
import postventa.reclamo.EstadoReclamo;
import postventa.reclamo.EstadoSla;
import postventa.reclamo.MotivoReclamo;
import postventa.reclamo.TipoReclamo;

/**
 * Respuesta de 2.10 y 2.10b. pedidoId, canal, nombreConsumidor, estadoSla y diasHabilesRestantes van además
 * de los campos del contrato: la bandeja del mockup 07 los muestra sin abrir cada reclamo.
 */
public record ListadoReclamosResponse(List<Resumen> contenido, int pagina, int totalPaginas, long totalElementos) {

    public record Resumen(String reclamoId, String codigoSeguimiento, TipoReclamo tipo, MotivoReclamo motivo,
                          EstadoReclamo estado, OffsetDateTime fechaRegistro, OffsetDateTime fechaLimiteSLA,
                          String pedidoId, CanalVenta canal, String nombreConsumidor,
                          EstadoSla estadoSla, Integer diasHabilesRestantes) {}
}

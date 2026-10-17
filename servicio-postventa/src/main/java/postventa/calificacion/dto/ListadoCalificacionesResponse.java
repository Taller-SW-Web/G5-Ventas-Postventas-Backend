package postventa.calificacion.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import postventa.compartido.CanalVenta;

/**
 * Calificaciones CSAT para el Gestor (mockup 09: detalle CSAT, distribución y comentarios por canal).
 * No está en el contrato. resumen se calcula sobre todo el filtro, no solo sobre la página.
 * distribucion tiene siempre las claves 1 a 5.
 */
public record ListadoCalificacionesResponse(List<Item> contenido, int pagina, int totalPaginas, long totalElementos,
                                            Resumen resumen) {

    public record Item(String pedidoId, int puntaje, String comentario, CanalVenta canal, LocalDate fecha) {}

    public record Resumen(long total, BigDecimal promedio, Map<Integer, Long> distribucion) {}
}

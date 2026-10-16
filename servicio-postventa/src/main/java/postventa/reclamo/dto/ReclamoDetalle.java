package postventa.reclamo.dto;

import java.time.OffsetDateTime;

import postventa.compartido.CanalVenta;
import postventa.reclamo.EstadoReclamo;
import postventa.reclamo.EstadoSla;
import postventa.reclamo.MotivoReclamo;
import postventa.reclamo.TipoReclamo;

/**
 * Respuesta de 2.9. Además de los campos del contrato trae consumidor, atendidoPor y el semáforo del plazo
 * (estadoSla, diasHabilesRestantes, slaCumplido) que usa la pantalla de Reclamos (mockup 07).
 * slaCumplido es null mientras no haya respuesta formal (ESP-14 CA-04, F6 CA-07).
 */
public record ReclamoDetalle(String reclamoId, String codigoSeguimiento, String pedidoId, TipoReclamo tipo,
                             CanalVenta canal, MotivoReclamo motivo, String detalle, EstadoReclamo estado,
                             int plazoDiasHabiles, OffsetDateTime fechaLimiteSLA, OffsetDateTime fechaRegistro,
                             OffsetDateTime fechaAtencion, String respuestaVisibleCliente,
                             Consumidor consumidor, String atendidoPor,
                             EstadoSla estadoSla, Integer diasHabilesRestantes, Boolean slaCumplido) {

    public record Consumidor(String nombreCompleto, String documento, String email, String telefono) {}
}

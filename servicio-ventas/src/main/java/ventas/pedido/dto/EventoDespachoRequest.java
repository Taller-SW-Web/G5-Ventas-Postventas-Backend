package ventas.pedido.dto;

import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import ventas.pedido.TipoEventoDespacho;

/** Cuerpo genérico del webhook de Despacho (api-ventas 1.5, formato de G2). */
public record EventoDespachoRequest(
        @NotBlank @Size(max = 90) String idEvento,      // idempotencia: el mismo idEvento no se procesa dos veces
        @NotBlank @Size(max = 30) String idPedido,      // PED-AAAA-NNNNN
        @Size(max = 60) String idDespacho,
        @NotNull TipoEventoDespacho tipo,
        String estadoAnterior,                          // estados de Despacho, solo informativos
        String estadoNuevo,
        OffsetDateTime ocurridoEn,
        Map<String, Object> datos) {}                   // nuevaFechaProgramada (REPROGRAMADO), motivoCierre (DEVUELTO)

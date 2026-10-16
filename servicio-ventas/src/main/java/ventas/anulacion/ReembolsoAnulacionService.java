package ventas.anulacion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ventas.compartido.CodigoPublico;
import ventas.integracion.PostventaClient;
import ventas.integracion.PostventaClient.Envio;
import ventas.integracion.PostventaClient.RespuestaReembolso;
import ventas.pedido.PedidoRepository;

/**
 * Reembolso de las anulaciones de pedidos ya cobrados (1.6 y rechazo de stock de Productos).
 * 1. registrar(): en la misma transacción que la anulación, guarda la solicitud PENDIENTE.
 * 2. enviar(): después del commit, la manda a M2 (POST /api/v2/reembolsos) y guarda el resultado.
 * 3. reintentarPendientes(): lo llama ReintentoReembolsosScheduler para las que siguen PENDIENTE (M2 caído, o M2
 *    respondió 201 con el reembolso PENDIENTE porque la pasarela no confirmó: se le vuelve a preguntar).
 * La X-Idempotency-Key es fija por anulación: reenviar nunca paga dos veces.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReembolsoAnulacionService {

    private static final int MAX_MOTIVO = 255;
    /** Estado del reembolso en M2 cuando la pasarela no confirmó (timeout): todavía no se sabe si se devolvió. */
    static final String REEMBOLSO_PENDIENTE_EN_M2 = "PENDIENTE";

    private final SolicitudReembolsoRepository solicitudes;
    private final PedidoRepository pedidos;
    private final PostventaClient postventa;

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(String anulacionId, Long idPedido, BigDecimal monto, String moneda, String motivo) {
        Long idAnulacion = CodigoPublico.id(anulacionId);
        if (solicitudes.findByIdAnulacion(idAnulacion).isPresent()) {
            return;   // la misma anulación ya la tiene
        }
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        SolicitudReembolso s = new SolicitudReembolso();
        s.setIdAnulacion(idAnulacion);
        s.setIdPedido(idPedido);
        s.setMonto(monto);
        s.setMoneda(moneda);
        s.setMotivo(motivo.length() <= MAX_MOTIVO ? motivo : motivo.substring(0, MAX_MOTIVO));
        s.setEstado(EstadoSolicitudReembolso.PENDIENTE);
        s.setCreadoEn(ahora);
        s.setActualizadoEn(ahora);
        solicitudes.save(s);
    }

    /**
     * Manda a M2 la solicitud de esa anulación, si sigue PENDIENTE. Si ya se envió, devuelve lo guardado sin
     * llamar a M2. Si no existe solicitud (anulación sin reembolso), devuelve null.
     */
    public RespuestaReembolso enviar(String anulacionId) {
        return solicitudes.findByIdAnulacion(CodigoPublico.id(anulacionId)).map(this::enviar).orElse(null);
    }

    /** Devuelve cuántas quedaron ENVIADA. Corta la vuelta si M2 no está disponible. */
    public int reintentarPendientes() {
        List<SolicitudReembolso> pendientes = solicitudes.findTop20ByEstadoOrderByCreadoEnAsc(EstadoSolicitudReembolso.PENDIENTE);
        int enviadas = 0;
        for (SolicitudReembolso s : pendientes) {
            Envio envio = enviar(s).envio();
            if (s.getEstado() == EstadoSolicitudReembolso.ENVIADA) {
                enviadas++;
            } else if (envio == Envio.NO_DISPONIBLE || envio == Envio.SIMULADO) {
                break;
            }
        }
        return enviadas;
    }

    private RespuestaReembolso enviar(SolicitudReembolso s) {
        String anulacionId = CodigoPublico.anulacion(s.getIdAnulacion());
        if (s.getEstado() != EstadoSolicitudReembolso.PENDIENTE) {
            Envio envio = s.getEstado() == EstadoSolicitudReembolso.ENVIADA ? Envio.ACEPTADO : Envio.RECHAZADO;
            return new RespuestaReembolso(envio, s.getReembolsoId(), null, s.getUltimoError());
        }
        String pedidoId = pedidos.findById(s.getIdPedido()).orElseThrow().getCodigo();
        RespuestaReembolso r = postventa.solicitarReembolso(anulacionId, pedidoId, s.getMonto(), s.getMoneda().trim(), s.getMotivo());
        if (r.envio() == Envio.SIMULADO) {
            return r;   // queda PENDIENTE: se envía de verdad cuando se conecte M2
        }
        s.setIntentos(s.getIntentos() + 1);
        s.setActualizadoEn(OffsetDateTime.now(ZoneOffset.UTC));
        switch (r.envio()) {
            case ACEPTADO -> {
                s.setReembolsoId(r.reembolsoId());
                if (REEMBOLSO_PENDIENTE_EN_M2.equals(r.estado())) {
                    // Sigue PENDIENTE: el scheduler vuelve a mandarla con la misma clave y F4 reintenta la pasarela
                    s.setUltimoError("M2 dejó el reembolso " + r.reembolsoId() + " PENDIENTE; se vuelve a consultar");
                } else {
                    s.setEstado(EstadoSolicitudReembolso.ENVIADA);
                    s.setUltimoError(null);
                }
            }
            case RECHAZADO -> {
                s.setEstado(EstadoSolicitudReembolso.RECHAZADA);
                s.setUltimoError(r.detalle());
                log.error("M2 rechazó el reembolso de {} ({}): {}. Lo debe revisar una persona", pedidoId, anulacionId, r.detalle());
            }
            default -> s.setUltimoError(r.detalle());
        }
        solicitudes.save(s);
        return r;
    }
}

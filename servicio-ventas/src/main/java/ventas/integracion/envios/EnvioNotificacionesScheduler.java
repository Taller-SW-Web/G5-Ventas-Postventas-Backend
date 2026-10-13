package ventas.integracion.envios;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ventas.integracion.envios.MarketplaceClient.Envio;

/**
 * Manda a Marketplace los avisos de notificacion_envio pendientes, del más antiguo al más nuevo (mismo criterio
 * que el outbox de M2): ACEPTADO marca enviado_en; RECHAZADO no se reintenta; NO_DISPONIBLE suma un intento y
 * corta la vuelta; tras MAXIMO_INTENTOS deja de reintentarse. Marketplace deduplica por eventId.
 * Solo existe con integracion.marketplace-simulado=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "integracion.marketplace-simulado", havingValue = "false")
public class EnvioNotificacionesScheduler {

    static final int MAXIMO_INTENTOS = 10;

    private final NotificacionEnvioRepository notificaciones;
    private final MarketplaceClient marketplace;

    @Scheduled(fixedDelayString = "${integracion.marketplace-intervalo-ms:5000}")
    public void enviarPendientes() {
        for (NotificacionEnvio n : notificaciones.findTop50ByEnviadoEnIsNullAndIntentosLessThanOrderByCreadoEnAsc(MAXIMO_INTENTOS)) {
            Envio envio = marketplace.notificar(n.getIdEvento(), n.getPayload());
            switch (envio) {
                case SIMULADO -> { return; }
                case ACEPTADO -> n.setEnviadoEn(OffsetDateTime.now(ZoneOffset.UTC));
                case RECHAZADO -> {
                    n.setIntentos(MAXIMO_INTENTOS);
                    log.error("Marketplace rechazó el aviso {} ({}): no se reintenta", n.getIdEvento(), n.getEstadoPublico());
                }
                case NO_DISPONIBLE -> n.setIntentos(n.getIntentos() + 1);
            }
            notificaciones.save(n);
            if (envio == Envio.NO_DISPONIBLE) {
                log.warn("Marketplace no disponible: se reintenta en la siguiente vuelta");
                return;
            }
        }
    }
}

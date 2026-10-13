package ventas.integracion.eventos;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ventas.integracion.PostventaClient;
import ventas.integracion.PostventaClient.Envio;

/**
 * Guía 5.8: manda a M2 (POST /api/v2/eventos) los eventos de outbox_evento que aún no se enviaron,
 * del más antiguo al más nuevo, hasta 50 por vuelta.
 * - ACEPTADO: marca enviado_en.
 * - RECHAZADO (400): suma un intento y sigue con el siguiente (un evento malo no frena a los demás).
 * - NO_DISPONIBLE: suma un intento y corta la vuelta (M2 caído); se reintenta en la siguiente.
 * Solo existe con integracion.postventa-simulado=false: en modo simulado los eventos se quedan en el outbox
 * y se envían todos cuando se conecte M2.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "integracion.postventa-simulado", havingValue = "false")
public class EnvioEventosScheduler {

    private final OutboxEventoRepository outbox;
    private final PostventaClient postventa;

    @Scheduled(fixedDelayString = "${integracion.eventos.intervalo-ms:5000}")
    public void enviarPendientes() {
        for (OutboxEvento evento : outbox.findTop50ByEnviadoEnIsNullOrderByCreadoEnAsc()) {
            Envio envio = postventa.enviarEvento(evento.getIdEvento(), evento.getPayload());
            if (envio == Envio.ACEPTADO) {
                evento.setEnviadoEn(OffsetDateTime.now(ZoneOffset.UTC));
            } else {
                evento.setIntentos(evento.getIntentos() + 1);
            }
            outbox.save(evento);
            if (envio == Envio.NO_DISPONIBLE) {
                log.warn("M2 no disponible: se reintenta el envío de eventos en la siguiente vuelta");
                return;
            }
        }
    }
}

package ventas.integracion.eventos;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ventas.integracion.PostventaClient.Envio;

/**
 * Guía 5.8: manda a M2 (HTTP o RabbitMQ, segun EVENTOS_ASINCRONOS) los eventos de outbox_evento que aún no se enviaron,
 * del más antiguo al más nuevo, hasta 50 por vuelta.
 * - ACEPTADO: HTTP 2xx o confirm positivo y sin retorno AMQP; marca enviado_en.
 * - RECHAZADO (400/409): no se reintenta nunca (intentos = MAXIMO_INTENTOS) y queda en el log para que lo
 *   revise una persona; sigue con el siguiente (un evento malo no frena a los demás).
 * - NO_DISPONIBLE: suma un intento y corta la vuelta (M2 caído). Tras MAXIMO_INTENTOS deja de reintentarse.
 * - SIMULADO: corta la vuelta sin contar intento; el evento queda pendiente para cuando se conecte M2.
 * Solo existe con integracion.postventa-simulado=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "integracion.postventa-simulado", havingValue = "false")
public class EnvioEventosScheduler {

    static final int MAXIMO_INTENTOS = 10;

    private final OutboxEventoRepository outbox;
    private final TransporteEventosPedido transporte;

    @Scheduled(fixedDelayString = "${integracion.eventos.intervalo-ms:5000}")
    public void enviarPendientes() {
        for (OutboxEvento evento : outbox.findTop50ByEnviadoEnIsNullAndIntentosLessThanOrderByCreadoEnAsc(MAXIMO_INTENTOS)) {
            Envio envio = transporte.enviar(evento);
            switch (envio) {
                case SIMULADO -> { return; }   // modo simulado: queda pendiente, sin contar intento
                case ACEPTADO -> evento.setEnviadoEn(OffsetDateTime.now(ZoneOffset.UTC));
                case RECHAZADO -> {
                    evento.setIntentos(MAXIMO_INTENTOS);   // no se reintenta: lo revisa una persona
                    log.error("M2 rechazó el evento {} ({}): no se reintenta", evento.getIdEvento(), evento.getTipo());
                }
                case NO_DISPONIBLE -> evento.setIntentos(evento.getIntentos() + 1);
            }
            outbox.save(evento);
            if (envio == Envio.NO_DISPONIBLE) {
                log.warn("Transporte a M2 no disponible: se reintenta el envio de eventos en la siguiente vuelta");
                return;
            }
        }
    }
}

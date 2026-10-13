package ventas.anulacion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reintenta cada minuto los reembolsos PENDIENTE. Solo existe cuando M1 llama a M2 de verdad. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "integracion.postventa-simulado", havingValue = "false")
public class ReintentoReembolsosScheduler {

    private final ReembolsoAnulacionService reembolsos;

    @Scheduled(fixedDelayString = "${integracion.reembolsos.intervalo-ms:60000}")
    public void reintentar() {
        int enviadas = reembolsos.reintentarPendientes();
        if (enviadas > 0) {
            log.info("Reintento de reembolsos: {} enviados a M2", enviadas);
        }
    }
}

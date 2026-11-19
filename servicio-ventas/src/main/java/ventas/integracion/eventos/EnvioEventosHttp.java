package ventas.integracion.eventos;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import ventas.integracion.PostventaClient;
import ventas.integracion.PostventaClient.Envio;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "integracion.eventos.asincronos", havingValue = "false", matchIfMissing = true)
public class EnvioEventosHttp implements TransporteEventosPedido {
    private final PostventaClient postventa;

    @Override
    public Envio enviar(OutboxEvento evento) {
        return postventa.enviarEvento(evento.getIdEvento(), evento.getPayload());
    }
}

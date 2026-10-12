package ventas.integracion;

import java.math.BigDecimal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Pide el cobro de un pedido MARKETPLACE al simulador de pasarela (POST /pasarela/v1/cobros, guía 9.4).
 * El resultado llega después por la 1.2. Si la pasarela no responde solo se registra en el log:
 * el pedido ya está creado y queda CREADO hasta que se cobre.
 */
@Slf4j
@Component
public class PasarelaClient {

    private record CobroRequest(String pedidoId, BigDecimal monto, String moneda, String metodoPago) {}

    private final boolean simulado;
    private final RestClient http;

    public PasarelaClient(@Value("${integracion.pasarela-simulada:true}") boolean simulado,
                          @Value("${integracion.pasarela-url:http://localhost:8083}") String url) {
        this.simulado = simulado;
        this.http = RestClient.builder().baseUrl(url).build();
    }

    public void iniciarCobro(String pedidoId, BigDecimal monto, String moneda, String metodoPago) {
        if (simulado) {
            log.info("[simulado] cobro de {} por {} {}", pedidoId, monto, moneda);
            return;
        }
        try {
            http.post().uri("/pasarela/v1/cobros")
                    .body(new CobroRequest(pedidoId, monto, moneda, metodoPago))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("No se pudo pedir el cobro de {} a la pasarela: {}", pedidoId, e.getMessage());
        }
    }
}

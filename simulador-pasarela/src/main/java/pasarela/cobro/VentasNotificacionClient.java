package pasarela.cobro;

import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import pasarela.cobro.dto.ConsultaCobroResponse;
import pasarela.cobro.dto.NotificacionPagoRequest;

/**
 * Envía el resultado de un cobro a M1 mediante HTTP.
 *
 * Cada invocación realiza un único intento. El servicio de cobros
 * gestionará los reintentos conservando el identificador de transacción.
 */
@Component
public class VentasNotificacionClient {

    private static final Logger LOG =
            LoggerFactory.getLogger(VentasNotificacionClient.class);

    private static final Duration TIEMPO_CONEXION = Duration.ofSeconds(2);
    private static final Duration TIEMPO_RESPUESTA = Duration.ofSeconds(3);

    private final RestClient http;
    private final String token;

    @Autowired
    public VentasNotificacionClient(
            @Value("${pasarela.ventas-url:http://localhost:8081}")
            String ventasUrl,
            @Value("${pasarela.token-m1:${PASARELA_TOKEN_M1:}}")
            String token) {

        this(crearCliente(ventasUrl), token);
    }

    /**
     * Permite probar el contrato HTTP con un cliente controlado.
     */
    VentasNotificacionClient(RestClient http, String token) {
        this.http = Objects.requireNonNull(http);
        this.token = Objects.requireNonNull(token).trim();
    }

    /**
     * Devuelve true únicamente cuando M1 responde HTTP 200.
     *
     * Un fallo de comunicación no modifica el resultado del cobro:
     * la notificación podrá intentarse nuevamente.
     */
    public boolean notificar(ConsultaCobroResponse transaccion) {
        Objects.requireNonNull(transaccion);

        EscenarioCobro resultado = switch (transaccion.estado()) {
            case APROBADO -> EscenarioCobro.APROBADO;
            case RECHAZADO -> EscenarioCobro.RECHAZADO;
            case PROCESANDO -> throw new IllegalArgumentException(
                    "No se puede notificar un cobro que sigue procesándose");
        };

        NotificacionPagoRequest solicitud = new NotificacionPagoRequest(
                transaccion.transaccionId(),
                resultado,
                transaccion.monto(),
                transaccion.moneda(),
                transaccion.metodoPago(),
                transaccion.fechaPago());

        try {
            Boolean aceptada = http.post()
                    .uri(
                            "/api/v1/pedidos/{pedidoId}/pagos/notificacion",
                            transaccion.pedidoId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(cabeceras -> {
                        if (!token.isBlank()) {
                            cabeceras.setBearerAuth(token);
                        }
                    })
                    .body(solicitud)
                    .exchange((peticion, respuesta) -> {
                        int estadoHttp = respuesta.getStatusCode().value();

                        if (estadoHttp != HttpStatus.OK.value()) {
                            LOG.warn(
                                    "M1 rechazó la notificación de la "
                                            + "transacción {} con HTTP {}",
                                    transaccion.transaccionId(),
                                    estadoHttp);
                        }

                        return estadoHttp == HttpStatus.OK.value();
                    });

            return Boolean.TRUE.equals(aceptada);
        } catch (RestClientException error) {
            LOG.warn(
                    "No se pudo notificar a M1 la transacción {}: {}",
                    transaccion.transaccionId(),
                    error.getMessage());

            return false;
        }
    }

    private static RestClient crearCliente(String ventasUrl) {
        Objects.requireNonNull(ventasUrl);

        SimpleClientHttpRequestFactory fabrica =
                new SimpleClientHttpRequestFactory();

        fabrica.setConnectTimeout(TIEMPO_CONEXION);
        fabrica.setReadTimeout(TIEMPO_RESPUESTA);

        return RestClient.builder()
                .baseUrl(ventasUrl)
                .requestFactory(fabrica)
                .build();
    }
}

package postventa.integracion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import postventa.compartido.CanalVenta;
import postventa.compartido.CodigoPublico;
import postventa.compartido.DependenciaNoDisponibleException;

/**
 * M1: consulta de un pedido (GET /api/v1/pedidos/{pedidoId}, api-ventas 1.3). Lo usan F3 y F5.
 *
 * Token: el de servicio de modulo-postventa (TokenServicio), con el scope ventas:pedidos:consultar que 1.3 acepta.
 * La titularidad del cliente la revisa F3, no M1. Hasta que Seguridad registre ese scope para modulo-postventa,
 * M1 responde 403 con la seguridad prendida; con SEGURIDAD_HABILITADA=false no se manda token.
 *
 * Con integracion.simulado=true no llama a M1 y responde un pedido ENTREGADO hace 2 días, del cliente
 * de prueba, por 129.90 PEN (PROD-101/SKU-101 x1 a 99.90 y PROD-999/SKU-999 x1 a 30.00). Para probar errores:
 * PED-AAAA-00404 no existe, PED-AAAA-00409 está DESPACHADO y PED-AAAA-00030 se entregó hace 30 días.
 */
@Slf4j
@Component
public class VentasClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PedidoM1(String pedidoId, String estado, ContactoM1 contacto, PagoM1 pago, List<ItemM1> items,
                    OffsetDateTime ultimaActualizacion, List<HistorialM1> historialEstados, String canal) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContactoM1(UUID clienteId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PagoM1(BigDecimal total, String moneda) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItemM1(String productoId, int cantidad, BigDecimal precioUnitario, String sku) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistorialM1(String estado, OffsetDateTime fecha) {}

    private final boolean simulado;
    private final UUID clientePrueba;
    private final TokenServicio tokenServicio;
    private final Clock reloj;
    private final RestClient http;

    @Autowired
    public VentasClient(@Value("${integracion.simulado:true}") boolean simulado,
                        @Value("${integracion.ventas-url:http://localhost:8081}") String url,
                        @Value("${seguridad.cliente-prueba:11111111-1111-1111-1111-111111111111}") UUID clientePrueba,
                        TokenServicio tokenServicio) {
        this(simulado, clientePrueba, tokenServicio, Clock.systemUTC(), RestClient.builder().baseUrl(url));
    }

    VentasClient(boolean simulado, UUID clientePrueba, TokenServicio tokenServicio, Clock reloj, RestClient.Builder http) {
        this.simulado = simulado;
        this.clientePrueba = clientePrueba;
        this.tokenServicio = tokenServicio;
        this.reloj = reloj;
        this.http = http.build();
    }

    /**
     * Vacío si el pedido no existe. Si M1 rechaza el token de M2 (403), lanza AccessDeniedException.
     * Si M1 no responde, 503 VENTAS_NO_DISPONIBLE.
     */
    public Optional<PedidoResumen> consultarPedido(String pedidoId) {
        if (simulado) {
            return simular(pedidoId);
        }
        try {
            PedidoM1 pedido = http.get().uri("/api/v1/pedidos/{pedidoId}", pedidoId)
                    .headers(h -> tokenServicio.authorization().ifPresent(t -> h.set(HttpHeaders.AUTHORIZATION, t)))
                    .retrieve()
                    .body(PedidoM1.class);
            return Optional.ofNullable(pedido).map(VentasClient::convertir);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new AccessDeniedException("Ventas rechazó la consulta del pedido con el token de servicio de M2");
            }
            throw new DependenciaNoDisponibleException("VENTAS_NO_DISPONIBLE",
                    "No se pudo consultar el pedido en Ventas", e);
        } catch (RestClientException e) {
            throw new DependenciaNoDisponibleException("VENTAS_NO_DISPONIBLE",
                    "No se pudo consultar el pedido en Ventas", e);
        }
    }

    static PedidoResumen convertir(PedidoM1 p) {
        List<PedidoResumen.Item> items = p.items() == null ? List.of() : p.items().stream()
                .map(i -> new PedidoResumen.Item(i.productoId(), i.cantidad(), i.precioUnitario(), i.sku()))
                .toList();
        String estado = limpiar(p.estado());
        OffsetDateTime fechaEntrega = null;
        if (PedidoResumen.ENTREGADO.equals(estado)) {
            fechaEntrega = p.historialEstados() == null ? null : p.historialEstados().stream()
                    .filter(h -> PedidoResumen.ENTREGADO.equals(limpiar(h.estado())) && h.fecha() != null)
                    .map(HistorialM1::fecha)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            if (fechaEntrega == null) {
                fechaEntrega = p.ultimaActualizacion();
            }
        }
        boolean pagoConfirmado = PedidoResumen.ENTREGADO.equals(estado)
                || "PAGADO".equals(estado)
                || (p.historialEstados() != null && p.historialEstados().stream()
                .anyMatch(h -> "PAGADO".equals(limpiar(h.estado()))));
        return new PedidoResumen(p.pedidoId(), estado,
                p.contacto() == null ? null : p.contacto().clienteId(),
                p.pago() == null ? null : p.pago().total(),
                p.pago() == null ? null : p.pago().moneda(),
                items, fechaEntrega, pagoConfirmado, canal(p.canal()));
    }

    /** Canal del pedido (MARKETPLACE, CHATBOT, RETAIL); null si M1 no lo manda o trae otro valor. */
    private static CanalVenta canal(String canal) {
        try {
            return canal == null ? null : CanalVenta.valueOf(canal.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** El ejemplo del contrato trae algunos estados entre comillas invertidas (`EN_PREPARACION`). */
    private static String limpiar(String estado) {
        return estado == null ? null : estado.replace("`", "").trim();
    }

    private Optional<PedidoResumen> simular(String pedidoId) {
        long numero = CodigoPublico.id(pedidoId);
        if (numero == 404) {
            return Optional.empty();
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC);
        boolean despachado = numero == 409;
        OffsetDateTime entrega = despachado ? null : ahora.minusDays(numero == 30 ? 30 : 2);
        log.info("[simulado] consulta del pedido {}", pedidoId);
        return Optional.of(new PedidoResumen(pedidoId, despachado ? "DESPACHADO" : PedidoResumen.ENTREGADO,
                clientePrueba, new BigDecimal("129.90"), "PEN",
                List.of(new PedidoResumen.Item("PROD-101", 1, new BigDecimal("99.90"), "SKU-101"),
                        new PedidoResumen.Item("PROD-999", 1, new BigDecimal("30.00"), "SKU-999")),
                entrega, true, CanalVenta.MARKETPLACE));
    }
}

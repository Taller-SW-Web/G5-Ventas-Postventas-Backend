package postventa.integracion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Módulo E (Despacho, G2): logística de un cambio de producto (recojo y nuevo envío, ESP-08 flujo B).
 * El acuerdo con Despacho sigue pendiente (CONSULTA_G2_DESPACHO), así que en modo real no se llama a
 * nadie y la coordinación queda sin confirmar: el expediente se mantiene APROBADA hasta reintentarlo.
 * Con integracion.simulado=true la confirma al instante.
 */
@Slf4j
@Component
public class DespachoClient {

    private final boolean simulado;

    public DespachoClient(@Value("${integracion.simulado:true}") boolean simulado) {
        this.simulado = simulado;
    }

    /** Referencia de la operación de Despacho si la confirmó; vacío si sigue pendiente. */
    public Optional<String> programarCambio(String devolucionId, String pedidoId, UUID clienteId,
                                            List<ProductosClient.ItemProducto> items) {
        if (simulado) {
            log.info("[simulado] logística del cambio {} del pedido {}: {}", devolucionId, pedidoId, items);
            return Optional.of("DESP-SIM-" + devolucionId);
        }
        // Pendiente: endpoint de Despacho para cambios, cuando exista ACUERDO_G2_DESPACHO
        log.warn("Logística del cambio {} pendiente: no hay acuerdo con Despacho todavía", devolucionId);
        return Optional.empty();
    }
}

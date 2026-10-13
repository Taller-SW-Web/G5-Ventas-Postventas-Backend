package postventa.integracion;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import postventa.reembolso.EstadoReembolso;

/** Adaptador F4; mientras Johan termina 8083, el modo local confirma el extorno. */
@Component
public class PasarelaClient {
    private final boolean simulada;
    public PasarelaClient(@Value("${integracion.pasarela-simulada:true}") boolean simulada) { this.simulada = simulada; }
    public ResultadoExtorno solicitarExtorno(String clave, String referencia, BigDecimal monto, String moneda) {
        if (simulada) return new ResultadoExtorno(EstadoReembolso.EXITOSO, "TX-SIM-" + UUID.randomUUID(), "Extorno confirmado");
        return new ResultadoExtorno(EstadoReembolso.PENDIENTE, null, "Pasarela externa pendiente de integrar");
    }
    public record ResultadoExtorno(EstadoReembolso estado, String transaccionId, String detalle) {}
}

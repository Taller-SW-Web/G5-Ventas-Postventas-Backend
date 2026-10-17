package postventa.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import postventa.compartido.CanalVenta;
import postventa.compartido.SolicitudInvalidaException;
import postventa.dashboard.AgregadoMetricasRepository.Contador;

/**
 * F6 (ESP-15): mantiene agregado_metricas, la única fuente del dashboard (2.12). Lo llaman, dentro de su propia
 * transacción, el receptor de eventos de M1 (2.13) y las operaciones de F3 a F6 (guía 10.4). Si la operación
 * de origen se revierte, la suma también: un hecho no se cuenta dos veces ni a medias.
 *
 * La fila es la de la fecha UTC del hecho (no la de recepción), así un evento atrasado cae en su periodo.
 * Un hecho sin canal no suma (no tiene fila) y nunca revierte la operación de origen.
 */
@Slf4j
@Service
public class ProyeccionesService {

    private final AgregadoMetricasRepository agregados;

    public ProyeccionesService(AgregadoMetricasRepository agregados) {
        this.agregados = agregados;
    }

    /**
     * Eventos de M1 (2.13). Lo llama el receptor de eventos después de registrar el idEvento en
     * postventa.evento_recibido, en la misma transacción. Tipo desconocido: 400, sin sumar nada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void aplicarEventoPedido(String tipo, OffsetDateTime fecha, CanalVenta canal) {
        Contador contador = switch (tipo == null ? "" : tipo) {
            case "pedido.pagado" -> Contador.PEDIDOS_PAGADOS;
            case "pedido.anulado" -> Contador.PEDIDOS_ANULADOS;
            case "pedido.entregado" -> Contador.PEDIDOS_ENTREGADOS;
            default -> throw new SolicitudInvalidaException("DATOS_INVALIDOS", "Tipo de evento no permitido: " + tipo);
        };
        sumar(fecha, canal, contador, 1);
    }

    /** F3: devolución aprobada (ESP-15 regla 3). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void devolucionAprobada(OffsetDateTime fecha, CanalVenta canal) {
        sumar(fecha, canal, Contador.DEVOLUCIONES_APROBADAS, 1);
    }

    /** F4: solo reembolsos confirmados en PEN suman (ESP-15 regla 4). Otra moneda se ignora. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reembolsoExitoso(OffsetDateTime fecha, CanalVenta canal, BigDecimal monto, String moneda) {
        if (!"PEN".equals(moneda) || monto == null || monto.signum() <= 0) {
            log.debug("Reembolso en {} por {} no suma a montoReembolsadoPEN", moneda, monto);
            return;
        }
        sumar(fecha, canal, Contador.MONTO_REEMBOLSADO_PEN, monto);
    }

    /** F5: encuesta CSAT válida (1 a 5). Fuera de rango no modifica el agregado (ESP-15 8.5). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void calificacionRegistrada(OffsetDateTime fecha, CanalVenta canal, int puntaje) {
        if (puntaje < 1 || puntaje > 5) {
            log.warn("Puntaje CSAT {} fuera de rango, no se suma", puntaje);
            return;
        }
        if (sinCanal(canal, "CSAT")) {
            return;
        }
        agregados.sumarCsat(dia(fecha), canal, puntaje);
    }

    /** F6: reclamo registrado (2.8). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reclamoRegistrado(OffsetDateTime fecha, CanalVenta canal) {
        sumar(fecha, canal, Contador.RECLAMOS_REGISTRADOS, 1);
    }

    /** F6: reclamo que pasa a ATENDIDO (2.11). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reclamoAtendido(OffsetDateTime fecha, CanalVenta canal) {
        sumar(fecha, canal, Contador.RECLAMOS_ATENDIDOS, 1);
    }

    private void sumar(OffsetDateTime fecha, CanalVenta canal, Contador contador, Number valor) {
        if (sinCanal(canal, contador.name())) {
            return;
        }
        agregados.sumar(dia(fecha), canal, contador, valor);
    }

    /** Por ejemplo, un pedido de M1 que llega sin canal desde F4: se registra en el log en vez de fallar. */
    private static boolean sinCanal(CanalVenta canal, String metrica) {
        if (canal == null) {
            log.warn("Hecho sin canal: no suma a {} en el dashboard", metrica);
            return true;
        }
        return false;
    }

    private static LocalDate dia(OffsetDateTime fecha) {
        return fecha.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    }
}

package postventa.devolucion;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import postventa.compartido.ConflictoException;
import postventa.compartido.SolicitudInvalidaException;
import postventa.devolucion.dto.ConfirmarRecepcionRequest;
import postventa.devolucion.dto.ConfirmarRecepcionResponse;
import postventa.integracion.PedidoResumen;
import postventa.integracion.ProductosClient;
import postventa.integracion.ProductosClient.LineaReintegro;
import postventa.integracion.VentasClient;

/**
 * 2.5b Confirmar recepción física (ESP-08 8.2b, reglas 14 a 16; guía 7.5b). La aprobación del Gestor no mueve
 * inventario: recién aquí, cuando la unidad llegó al almacén, se reintegran a Productos las unidades aptas.
 *
 * - Cada confirmación es una operación con su operation_id. Una recepción parcial posterior es otra operación.
 * - Las filas se guardan PENDIENTE antes de llamar a Productos. Si Productos no responde (503), quedan PENDIENTE y la
 *   siguiente llamada las reenvía con el mismo operation_id: Productos nunca reintegra dos veces (G6 P-05).
 * - El estado no cambia: sigue APROBADA y pasa a COMPLETADA cuando ya no falta nada (DevolucionService).
 */
@Slf4j
@Service
public class RecepcionService {

    private final DevolucionService devoluciones;
    private final RecepcionDevolucionRepository recepciones;
    private final HistorialDevolucionRepository historial;
    private final VentasClient ventas;
    private final ProductosClient productos;
    private final SolicitadorReembolso reembolsos;
    private final TransactionTemplate tx;
    private final Clock reloj;

    @Autowired
    public RecepcionService(DevolucionService devoluciones, RecepcionDevolucionRepository recepciones,
                            HistorialDevolucionRepository historial, VentasClient ventas, ProductosClient productos,
                            SolicitadorReembolso reembolsos, PlatformTransactionManager transacciones) {
        this(devoluciones, recepciones, historial, ventas, productos, reembolsos,
                new TransactionTemplate(transacciones), Clock.systemUTC());
    }

    RecepcionService(DevolucionService devoluciones, RecepcionDevolucionRepository recepciones,
                     HistorialDevolucionRepository historial, VentasClient ventas, ProductosClient productos,
                     SolicitadorReembolso reembolsos, TransactionTemplate tx, Clock reloj) {
        this.devoluciones = devoluciones;
        this.recepciones = recepciones;
        this.historial = historial;
        this.ventas = ventas;
        this.productos = productos;
        this.reembolsos = reembolsos;
        this.tx = tx;
        this.reloj = reloj;
    }

    /** Recibidas y aptas de una línea, sumando las líneas repetidas del mismo sku. */
    private record Cantidades(int recibida, int apta) {
        Cantidades mas(Cantidades otra) {
            return new Cantidades(recibida + otra.recibida, apta + otra.apta);
        }
    }

    public ConfirmarRecepcionResponse confirmar(String devolucionId, ConfirmarRecepcionRequest solicitud,
                                                Solicitante quien) {
        Map<String, Cantidades> pedidas = agrupar(solicitud.lineas());
        Devolucion d = devoluciones.buscar(devolucionId, true);
        boolean resolucionConProducto = d.getResolucion() == TipoResolucion.CAMBIO
                || d.getResolucion() == TipoResolucion.REEMBOLSO;
        if (d.getEstado() != EstadoDevolucion.APROBADA || !resolucionConProducto || !d.isRequiereDevolucionFisica()) {
            throw new ConflictoException("RESOLUCION_NO_APROBADA",
                    "La devolución " + d.codigo() + " no está aprobada con devolución física del producto.");
        }

        // 1. Reintentos: las recepciones que quedaron PENDIENTE se reenvían con su mismo operation_id
        List<RecepcionDevolucion> previas = recepciones.findByDevolucionIdOrderByIdAsc(d.getId());
        Map<UUID, List<RecepcionDevolucion>> pendientes = previas.stream()
                .filter(r -> r.getReintegroEstado() == EstadoReintegro.PENDIENTE)
                .collect(Collectors.groupingBy(RecepcionDevolucion::getOperationId, LinkedHashMap::new, Collectors.toList()));
        List<RecepcionDevolucion> repetida = null;
        for (List<RecepcionDevolucion> operacion : pendientes.values()) {
            reintegrar(d, operacion);
            if (cantidades(operacion).equals(pedidas)) {
                repetida = operacion;
            }
        }
        if (repetida != null) {
            log.info("Recepción de la devolución {} reintentada con operation_id {}", d.codigo(),
                    repetida.get(0).getOperationId());
            return respuesta(d, repetida);
        }

        // 2. Recepción nueva
        PedidoResumen pedido = ventas.consultarPedido(DevolucionService.codigoPedido(d))
                .orElseThrow(() -> new ConflictoException("PEDIDO_INCONSISTENTE",
                        "Ventas ya no encuentra el pedido de la devolución " + d.codigo() + "."));
        List<RecepcionDevolucion> nuevas = nuevaRecepcion(d, pedido, previas, pedidas, solicitud.evidencia(), quien);
        String resumen = nuevas.stream()
                .map(r -> r.getSku() + " recibidas " + r.getCantidadRecibida() + ", aptas " + r.getCantidadApta())
                .collect(Collectors.joining("; "));
        tx.executeWithoutResult(t -> {
            recepciones.saveAll(nuevas);
            historial.save(HistorialDevolucion.de(d, EstadoDevolucion.APROBADA, EstadoDevolucion.APROBADA,
                    quien.actor(), quien.idActor(), "Recepción física: " + resumen, nuevas.get(0).getFechaHora()));
        });
        reintegrar(d, nuevas);
        log.info("Recepción física de la devolución {}: {}", d.codigo(), resumen);
        return respuesta(d, nuevas);
    }

    private static Map<String, Cantidades> agrupar(List<ConfirmarRecepcionRequest.Linea> lineas) {
        Map<String, Cantidades> pedidas = new LinkedHashMap<>();
        for (ConfirmarRecepcionRequest.Linea l : lineas) {
            if (l.cantidadApta() > l.cantidadRecibida()) {
                throw new SolicitudInvalidaException("CANTIDAD_INVALIDA",
                        "En " + l.sku() + " hay más unidades aptas que recibidas.");
            }
            pedidas.merge(l.sku().trim(), new Cantidades(l.cantidadRecibida(), l.cantidadApta()), Cantidades::mas);
        }
        return pedidas;
    }

    private static Map<String, Cantidades> cantidades(List<RecepcionDevolucion> filas) {
        Map<String, Cantidades> c = new LinkedHashMap<>();
        filas.forEach(r -> c.merge(r.getSku(), new Cantidades(r.getCantidadRecibida(), r.getCantidadApta()), Cantidades::mas));
        return c;
    }

    /**
     * Cada sku tiene que ser una línea del pedido que se está devolviendo, y no se pueden recibir más unidades de
     * las devueltas. Si ya llegaron todas: 409 RECEPCION_YA_CONFIRMADA (no se duplica el reintegro).
     */
    private List<RecepcionDevolucion> nuevaRecepcion(Devolucion d, PedidoResumen pedido,
                                                     List<RecepcionDevolucion> previas, Map<String, Cantidades> pedidas,
                                                     String evidencia, Solicitante quien) {
        Map<String, Integer> devueltas = new LinkedHashMap<>();
        d.getItems().forEach(i -> devueltas.merge(i.getIdProducto(), i.getCantidad(), Integer::sum));
        Map<String, Integer> recibidasAntes = new LinkedHashMap<>();
        previas.forEach(r -> recibidasAntes.merge(r.getIdProducto(), r.getCantidadRecibida(), Integer::sum));

        UUID operacion = UUID.randomUUID();
        OffsetDateTime ahora = OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        List<RecepcionDevolucion> nuevas = new ArrayList<>();
        pedidas.forEach((sku, c) -> {
            String idProducto = pedido.items().stream()
                    .filter(i -> sku.equals(i.sku()))
                    .map(PedidoResumen.Item::productoId)
                    .findFirst()
                    .filter(devueltas::containsKey)
                    .orElseThrow(() -> new SolicitudInvalidaException("CANTIDAD_INVALIDA",
                            "El SKU " + sku + " no pertenece a las líneas devueltas del pedido."));
            int devuelta = devueltas.get(idProducto);
            int antes = recibidasAntes.getOrDefault(idProducto, 0);
            if (antes >= devuelta) {
                throw new ConflictoException("RECEPCION_YA_CONFIRMADA",
                        "Ya se confirmó la recepción de todas las unidades de " + sku + ".");
            }
            if (antes + c.recibida() > devuelta) {
                throw new SolicitudInvalidaException("CANTIDAD_INVALIDA",
                        "De " + sku + " se devolvieron " + devuelta + " unidades y ya se recibieron " + antes + ".");
            }
            RecepcionDevolucion r = new RecepcionDevolucion();
            r.setDevolucion(d);
            r.setOperationId(operacion);
            r.setSku(sku);
            r.setIdProducto(idProducto);
            r.setCantidadRecibida(c.recibida());
            r.setCantidadApta(c.apta());
            r.setReintegroEstado(EstadoReintegro.PENDIENTE);
            r.setEvidencia(evidencia == null || evidencia.isBlank() ? null : evidencia.trim());
            r.setIdActor(quien.idActor());
            r.setFechaHora(ahora);
            nuevas.add(r);
        });
        return nuevas;
    }

    /** Pide a Productos solo las unidades aptas (regla 15) y deja la operación COMPLETADO. Si no hay aptas, no llama. */
    private void reintegrar(Devolucion d, List<RecepcionDevolucion> operacion) {
        List<LineaReintegro> lineas = operacion.stream()
                .filter(r -> r.getCantidadApta() > 0)
                .map(r -> new LineaReintegro(r.getSku(), r.getCantidadApta()))
                .toList();
        if (!lineas.isEmpty()) {
            productos.reintegrar(operacion.get(0).getOperationId(), DevolucionService.codigoPedido(d), d.codigo(), lineas);
        }
        operacion.forEach(r -> r.setReintegroEstado(EstadoReintegro.COMPLETADO));
        tx.executeWithoutResult(t -> recepciones.saveAll(operacion));
    }

    /** Con la recepción al día, se completa si F4 confirmó el reembolso o M1 ya entregó el reemplazo. */
    private ConfirmarRecepcionResponse respuesta(Devolucion d, List<RecepcionDevolucion> operacion) {
        // F4 lee los intentos del reembolso de forma perezosa: la consulta tiene que ir dentro de una transacción
        boolean confirmada = d.getResolucion() == TipoResolucion.REEMBOLSO
                ? Boolean.TRUE.equals(tx.execute(t -> reembolsos.consultar(d.codigo())
                        .map(SolicitadorReembolso.Resultado::exitoso).orElse(false)))
                : devoluciones.cambioEntregado(d);
        boolean completada = devoluciones.completarSiCorresponde(d, d.getResolucion(), confirmada, d.getReferenciaOperacion());
        RecepcionDevolucion primera = operacion.get(0);
        return new ConfirmarRecepcionResponse(d.codigo(), true, primera.getFechaHora(), primera.getIdActor(),
                operacion.stream().map(r -> new ConfirmarRecepcionResponse.Linea(r.getSku(), r.getCantidadApta())).toList(),
                operacion.stream().anyMatch(r -> r.getCantidadApta() > 0),
                completada ? EstadoDevolucion.COMPLETADA : EstadoDevolucion.APROBADA);
    }
}

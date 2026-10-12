package ventas.pedido;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import ventas.compartido.CodigoPublico;
import ventas.compartido.ConflictoException;
import ventas.integracion.PasarelaClient;
import ventas.integracion.ProductosClient;
import ventas.pedido.dto.CrearPedidoRequest;
import ventas.pedido.dto.CrearPedidoResponse;

/**
 * 1.1 Crear pedido (ESP-01) con idempotencia (acuerdo H-02).
 * Orden: validar -> ¿clave ya usada? -> reservar stock -> guardar todo en una transacción -> cobro.
 * MARKETPLACE: nace CREADO y se pide el cobro a la pasarela. CHATBOT: nace CREADO (el chatbot cobra y avisa por 1.2).
 * RETAIL: nace PAGADO y pasa a ENTREGADO en la misma transacción (acuerdo G4).
 */
@Service
@RequiredArgsConstructor
public class CrearPedidoService {

    static final String MOTIVO_INICIAL = "Registro inicial de compra";
    static final String MOTIVO_ENTREGA_TIENDA = "ENTREGA_INMEDIATA_EN_TIENDA";

    /** creado = true si se registró ahora (201); false si es una repetición de la misma clave (200). */
    public record Resultado(CrearPedidoResponse pedido, boolean creado) {}

    private final ValidadorPedido validador;
    private final PedidoRepository pedidos;
    private final PagoRepository pagos;
    private final MaquinaEstados maquina;
    private final ProductosClient productos;
    private final PasarelaClient pasarela;
    private final TransactionOperations transaccion;

    public Resultado crear(String clave, CrearPedidoRequest req) {
        validador.validarClave(clave);
        validador.validar(req);

        Optional<Pedido> previo = pedidos.findByIdempotencyKey(clave);
        if (previo.isPresent()) {
            return repetido(previo.get(), req);
        }

        // Retail ya vendió en tienda: no se reserva stock (acuerdo G4)
        boolean reservado = req.canal() != CanalVenta.RETAIL;
        if (reservado) {
            productos.reservar(clave, req.items().stream()
                    .map(i -> new ProductosClient.ItemReserva(i.productoId(), i.sku(), i.cantidad()))
                    .toList());
        }

        Pedido pedido;
        try {
            pedido = transaccion.execute(estado -> registrar(clave, req));
        } catch (DataIntegrityViolationException e) {
            Optional<Pedido> ganador = pedidos.findByIdempotencyKey(clave);
            if (ganador.isPresent()) {
                // Otra petición con la misma clave ganó la carrera. La reserva usa la misma referencia
                // (la clave), así que es la del pedido ganador: no se libera
                return repetido(ganador.get(), req);
            }
            liberarReserva(reservado, clave);
            throw e;
        } catch (RuntimeException e) {
            // El pedido no se guardó: la reserva quedaría colgada en Productos
            liberarReserva(reservado, clave);
            throw e;
        }
        if (req.canal() == CanalVenta.MARKETPLACE) {
            // Después del commit: si la pasarela falla, el pedido queda CREADO y se puede reintentar el cobro
            pasarela.iniciarCobro(pedido.getCodigo(), pedido.getTotal(), pedido.getMoneda().trim(), req.pago().metodoPago());
        }
        return new Resultado(respuesta(pedido), true);
    }

    private void liberarReserva(boolean reservado, String clave) {
        if (reservado) {
            productos.liberar(clave);
        }
    }

    private Pedido registrar(String clave, CrearPedidoRequest req) {
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        CrearPedidoRequest.Pago p = req.pago();
        OffsetDateTime fechaHora = req.canal() == CanalVenta.RETAIL && p.fechaVenta() != null ? p.fechaVenta() : ahora;

        Pedido pedido = nuevoPedido(clave, req, ahora, fechaHora);
        pedidos.saveAndFlush(pedido);
        pedido.setCodigo(CodigoPublico.pedido(pedido.getId(), fechaHora.getYear()));

        Pago pago = new Pago();
        pago.setPedido(pedido);
        pago.setMetodo(p.metodoPago());
        pago.setMonto(p.total());
        pago.setReferencia(p.referenciaOperacion());
        if (pedido.getEstado() == EstadoPedido.PAGADO) {
            pago.setEstado(EstadoPago.CONFIRMADO);
            pago.setFechaProceso(fechaHora);
        } else {
            pago.setEstado(EstadoPago.PENDIENTE);
        }
        pagos.save(pago);

        ActorHistorial actor = req.canal() == CanalVenta.CHATBOT ? ActorHistorial.CANAL_CHATBOT : ActorHistorial.CLIENTE;
        // El registro inicial lleva la hora real de la llamada; fechaVenta solo va en PAGADO -> ENTREGADO (acuerdo G4, R8)
        maquina.registrarInicial(pedido, actor, MOTIVO_INICIAL, ahora);

        if (req.canal() == CanalVenta.RETAIL) {
            // Acuerdo G4: el cliente se lleva el producto en el momento
            maquina.cambiar(pedido, EstadoPedido.ENTREGADO, ActorHistorial.CLIENTE, MOTIVO_ENTREGA_TIENDA, fechaHora);
        }
        return pedido;
    }

    private Pedido nuevoPedido(String clave, CrearPedidoRequest req, OffsetDateTime ahora, OffsetDateTime fechaHora) {
        CrearPedidoRequest.Contacto c = req.contacto();
        CrearPedidoRequest.Pago p = req.pago();
        CrearPedidoRequest.Envio e = req.envio();

        Pedido pedido = new Pedido();
        pedido.setCodigo(CodigoPublico.temporal());
        pedido.setIdempotencyKey(clave);
        pedido.setCanal(req.canal());
        // Retail nace pagado (el cobro ya pasó por el POS) y se entrega enseguida; los demás esperan la 1.2
        pedido.setEstado(req.canal() == CanalVenta.RETAIL ? EstadoPedido.PAGADO : EstadoPedido.CREADO);
        pedido.setFecha(fechaHora.toLocalDate());
        pedido.setCreadoEn(ahora);
        pedido.setIdCliente(c.clienteId());
        pedido.setNombreContacto(c.nombreCompleto());
        pedido.setTipoDocumento(c.tipoDocumento().name());
        pedido.setNumeroDocumento(c.numeroDocumento());
        pedido.setTelefono(c.telefono());
        pedido.setEmail(c.email());
        if (req.canal() == CanalVenta.RETAIL) {
            // La tabla no tiene columna de tienda: se guarda como modalidad + dirección
            pedido.setModalidadEnvio("ENTREGA_EN_TIENDA");
            pedido.setDireccion(e.tiendaId());
        } else {
            pedido.setModalidadEnvio(e.modalidad());
            pedido.setDestinatario(e.destinatario());
            pedido.setDistrito(e.distrito());
            pedido.setDireccion(e.direccion());
        }
        pedido.setCostoEnvio(cero(p.costoEnvio()));
        if (req.cupon() != null) {
            pedido.setCodigoCupon(req.cupon().codigo());
        }
        pedido.setDescuento(cero(p.descuentoCupon()));
        pedido.setMoneda(p.moneda());
        pedido.setSubtotal(p.subtotal());
        pedido.setTotal(p.total());

        for (CrearPedidoRequest.Item i : req.items()) {
            DetallePedido d = new DetallePedido();
            d.setIdProducto(idProducto(i.productoId()));
            d.setSku(i.sku());
            d.setDescripcion(i.descripcion());
            d.setCantidad(i.cantidad());
            d.setPrecioUnitario(i.precioUnitario());
            d.setDescuento(BigDecimal.ZERO);
            d.setImporte(i.precioUnitario().multiply(BigDecimal.valueOf(i.cantidad())));
            pedido.agregarDetalle(d);
        }
        return pedido;
    }

    /** Misma clave: si el cuerpo es el mismo devuelve el pedido ya creado; si cambió, 409. */
    private Resultado repetido(Pedido existente, CrearPedidoRequest req) {
        if (!mismoPedido(existente, req)) {
            throw new ConflictoException("IDEMPOTENCY_KEY_REUSED",
                    "La Idempotency-Key ya se usó con un pedido distinto");
        }
        return new Resultado(respuesta(existente), false);
    }

    static boolean mismoPedido(Pedido p, CrearPedidoRequest req) {
        if (!Objects.equals(p.getIdCliente(), req.contacto().clienteId())
                || p.getCanal() != req.canal()
                || !p.getMoneda().trim().equals(req.pago().moneda())
                || p.getTotal().compareTo(req.pago().total()) != 0
                || p.getDetalles().size() != req.items().size()) {
            return false;
        }
        List<String> guardados = p.getDetalles().stream()
                .map(d -> linea(d.getSku(), d.getCantidad(), d.getPrecioUnitario()))
                .sorted(Comparator.naturalOrder()).toList();
        List<String> recibidos = req.items().stream()
                .map(i -> linea(i.sku(), i.cantidad(), i.precioUnitario()))
                .sorted(Comparator.naturalOrder()).toList();
        return guardados.equals(recibidos);
    }

    private static String linea(String sku, int cantidad, BigDecimal precio) {
        return sku + "|" + cantidad + "|" + precio.stripTrailingZeros().toPlainString();
    }

    static CrearPedidoResponse respuesta(Pedido p) {
        return new CrearPedidoResponse(p.getCodigo(), p.getEstado(), p.getTotal(), p.getMoneda().trim(),
                p.getCanal(), p.getCreadoEn());
    }

    /** "PROD-101" -> 101. Si no termina en número queda null (el sku siempre se guarda). */
    static Long idProducto(String productoId) {
        String numero = productoId.replaceAll("^.*?(\\d+)$", "$1");
        return numero.equals(productoId) && !productoId.matches("\\d+") ? null : Long.valueOf(numero);
    }

    private static BigDecimal cero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

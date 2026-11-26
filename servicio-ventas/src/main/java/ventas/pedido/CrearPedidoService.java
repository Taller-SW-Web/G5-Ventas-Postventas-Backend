package ventas.pedido;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import ventas.compartido.CodigoPublico;
import ventas.compartido.ConflictoException;
import ventas.compartido.NoProcesableException;
import ventas.integracion.MensajeInventario;
import ventas.integracion.ProductosClient;
import ventas.integracion.ReservaSinRespuestaException;
import ventas.pedido.dto.CrearPedidoRequest;
import ventas.pedido.dto.CrearPedidoResponse;

/**
 * 1.1 Crear pedido (ESP-01) con idempotencia (acuerdo H-02).
 * Orden: validar -> ¿clave ya usada? -> en una transacción: guardar y reservar stock (con el código del pedido).
 * MARKETPLACE: nace CREADO; el cobro se pide cuando Productos avisa que la reserva quedó creada (MensajesProductosService).
 * CHATBOT: nace CREADO (el chatbot cobra y avisa por 1.2).
 * RETAIL: nace PAGADO y pasa a ENTREGADO en la misma transacción (acuerdo G4); reserva y confirma el consumo en
 * Productos como los demás canales (acuerdo G4 + G6, 26/11/2026, reemplaza R9).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrearPedidoService {

    static final String MOTIVO_INICIAL = "Registro inicial de compra";
    static final String MOTIVO_ENTREGA_TIENDA = "ENTREGA_INMEDIATA_EN_TIENDA";

    /** creado = true si se registró ahora (201); false si es una repetición de la misma clave (200). */
    public record Resultado(CrearPedidoResponse pedido, boolean creado) {}

    private record Registro(Pedido pedido, Optional<MensajeInventario> reserva) {}

    private final ValidadorPedido validador;
    private final PedidoRepository pedidos;
    private final PagoRepository pagos;
    private final MaquinaEstados maquina;
    private final ProductosClient productos;
    private final MensajesProductosService mensajesProductos;
    private final TransactionOperations transaccion;

    public Resultado crear(String clave, CrearPedidoRequest req) {
        return crear(clave, req, null);
    }

    /** clientIdCanal: client_id del token de servicio que llama (RNF-03). Null sin seguridad. */
    public Resultado crear(String clave, CrearPedidoRequest req, String clientIdCanal) {
        validador.validarClave(clave);
        validador.validar(req);

        Optional<Pedido> previo = pedidos.findByIdempotencyKey(clave);
        if (previo.isPresent()) {
            return repetido(previo.get(), req);
        }
        validarPedidoOrigen(req);

        Registro registro;
        try {
            registro = transaccion.execute(estado -> {
                Pedido p = registrar(clave, req, clientIdCanal);
                // Se reserva con el código ya asignado (order_id), dentro de la transacción: si Productos rechaza el
                // comando (4xx o sin conexión), el pedido no se guarda y la Idempotency-Key no se consume.
                // Retail también reserva (acuerdo G4 + G6 del 26/11, reemplaza R9): nace PAGADO/ENTREGADO y la
                // reserva creada se confirma al instante (MensajesProductosService). Si Productos no admite el
                // comando responde 503 y el POS reintenta con la misma Idempotency-Key (cola offline de G4)
                try {
                    return new Registro(p, productos.reservar(p.getCodigo(), req.canal().name(), req.items().stream()
                            .map(i -> new ProductosClient.LineaReserva(i.sku(), i.cantidad())).toList()));
                } catch (ReservaSinRespuestaException e) {
                    // Contrato H5 D-07: Productos pudo haberla admitido. El pedido se guarda (queda CREADO, sin reserva)
                    // y SeguimientoProductos consulta el resultado o la reenvía con la misma identidad y líneas
                    log.warn("Reserva de {} sin respuesta de Productos: el pedido se guarda y queda en seguimiento",
                            p.getCodigo());
                    return new Registro(p, Optional.empty());
                }
            });
        } catch (DataIntegrityViolationException e) {
            // Otra petición con la misma clave ganó la carrera: se responde con su pedido
            return pedidos.findByIdempotencyKey(clave).map(p -> repetido(p, req)).orElseThrow(() -> e);
        }
        Pedido pedido = registro.pedido();
        // En modo simulado la reserva creada llega al instante; en modo real llega por po.sales.q.
        // Ahí se guarda el reservaId y, en MARKETPLACE, se pide el cobro (con la reserva ya confirmada)
        registro.reserva().ifPresent(m -> {
            try {
                mensajesProductos.procesar(m);
            } catch (RuntimeException e) {
                // El pedido ya está guardado: no se responde error al canal por esto
                log.error("No se pudo aplicar la reserva simulada de {}", pedido.getCodigo(), e);
            }
        });
        return new Resultado(respuesta(pedido), true);
    }

    /** CREDITO_CAMBIO (acuerdo G4, 26/11): la prenda devuelta tiene que venir de un pedido entregado que exista. */
    private void validarPedidoOrigen(CrearPedidoRequest req) {
        if (req.canal() != CanalVenta.RETAIL || !"CREDITO_CAMBIO".equals(req.pago().metodoPago())) {
            return;
        }
        String codigo = req.pago().pedidoOrigenId().trim().toUpperCase();
        Pedido origen = pedidos.findByCodigo(codigo).orElseThrow(() -> new NoProcesableException(
                "PEDIDO_ORIGEN_NO_ENCONTRADO", "El pedido de la prenda devuelta no existe: " + codigo));
        if (origen.getEstado() != EstadoPedido.ENTREGADO) {
            throw new NoProcesableException("PEDIDO_ORIGEN_NO_ENTREGADO",
                    "Solo se puede cambiar una prenda de un pedido ENTREGADO");
        }
        // Cada prenda devuelta tiene que ser del pedido origen y no más unidades de las compradas
        for (CrearPedidoRequest.ItemDevuelto d : req.pago().itemsDevueltos()) {
            int compradas = origen.getDetalles().stream().filter(x -> d.sku().equals(x.getSku()))
                    .mapToInt(DetallePedido::getCantidad).sum();
            if (compradas < d.cantidad()) {
                throw new NoProcesableException("ITEM_DEVUELTO_INVALIDO",
                        "El sku " + d.sku() + " no está en " + codigo + " o se devuelven más unidades de las compradas");
            }
        }
    }

    /** "SKU:cantidad" separados por coma; se mandan a M2 en pedido.entregado para el reintegro. */
    static String itemsDevueltos(List<CrearPedidoRequest.ItemDevuelto> items) {
        return items.stream().map(i -> i.sku().trim() + ":" + i.cantidad())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private Pedido registrar(String clave, CrearPedidoRequest req, String clientIdCanal) {
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        CrearPedidoRequest.Pago p = req.pago();
        OffsetDateTime fechaHora = req.canal() == CanalVenta.RETAIL && p.fechaVenta() != null ? p.fechaVenta() : ahora;

        Pedido pedido = nuevoPedido(clave, req, ahora, fechaHora, clientIdCanal);
        pedidos.saveAndFlush(pedido);
        pedido.setCodigo(CodigoPublico.pedido(pedido.getId(), fechaHora.getYear()));

        Pago pago = new Pago();
        pago.setPedido(pedido);
        pago.setMetodo(p.metodoPago());
        pago.setMonto(p.total());
        String referencia = (p.referenciaOperacion() != null && !p.referenciaOperacion().isBlank())
                ? p.referenciaOperacion()
                : p.comprobanteReferencia();
        pago.setReferencia(referencia);
        pago.setMontoEfectivo(p.montoEfectivo());
        pago.setMontoTarjeta(p.montoTarjeta());
        pago.setFechaVenta(p.fechaVenta());
        pago.setMontoCreditoCambio(p.montoCreditoCambio());
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

    private Pedido nuevoPedido(String clave, CrearPedidoRequest req, OffsetDateTime ahora, OffsetDateTime fechaHora,
                               String clientIdCanal) {
        CrearPedidoRequest.Contacto c = req.contacto();
        CrearPedidoRequest.Pago p = req.pago();
        CrearPedidoRequest.Envio e = req.envio();

        Pedido pedido = new Pedido();
        pedido.setCodigo(CodigoPublico.temporal());
        pedido.setIdempotencyKey(clave);
        pedido.setCanal(req.canal());
        pedido.setClientIdCanal(clientIdCanal);
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
            // Venta en tienda (acuerdo G4, R5): sin envío a domicilio
            pedido.setModalidadEnvio("ENTREGA_EN_TIENDA");
            pedido.setTiendaId(e.tiendaId());
            if ("CREDITO_CAMBIO".equals(p.metodoPago())) {
                pedido.setCambioPedidoOrigen(p.pedidoOrigenId().trim().toUpperCase());
                pedido.setCambioItemsDevueltos(itemsDevueltos(p.itemsDevueltos()));
            }
        } else {
            pedido.setModalidadEnvio(e.modalidad());
            pedido.setTiendaId(e.tiendaId());
            pedido.setDestinatario(e.destinatario());
            pedido.setDistrito(e.distrito());
            pedido.setDireccion(e.direccion());
            pedido.setDepartamento(e.departamento());
            pedido.setProvincia(e.provincia());
            pedido.setReferenciaEnvio(e.referencia());
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
            d.setIdProducto(i.productoId());
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

    private static BigDecimal cero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

package ventas.anulacion;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ventas.anulacion.dto.*;
import ventas.compartido.CodigoPublico;
import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.DespachoClient;
import ventas.integracion.DespachoClient.Cancelacion;
import ventas.integracion.ProductosClient;
import ventas.integracion.ProductosClient.MotivoLiberacion;
import ventas.pedido.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnulacionService {

    private final PedidoRepository pedidos;
    private final AnulacionRepository anulaciones;
    private final MaquinaEstados maquinaEstados;
    private final ReembolsoAnulacionService reembolsos;
    private final ProductosClient productos;
    private final DespachoClient despacho;

    /**
     * Endpoint 1.7: POST /api/v1/pedidos/{pedidoId}/anulaciones
     */
    @Transactional
    public SolicitarAnulacionResponse solicitarAnulacion(String pedidoCodigo, SolicitarAnulacionRequest req, ActorHistorial actorSolicitante) {
        // 1. Validar motivo contra el catálogo cerrado
        MotivoAnulacion motivoEnum;
        try {
            motivoEnum = MotivoAnulacion.valueOf(req.motivo().trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new SolicitudInvalidaException("MOTIVO_INVALIDO", "Motivo de anulación ausente o no tipificado en el catálogo");
        }

        // 2. Buscar pedido
        Pedido pedido = pedidos.findByCodigo(pedidoCodigo)
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO", "No se encontró el pedido " + pedidoCodigo));

        EstadoPedido estadoActual = pedido.getEstado();

        // 3. Validar estado anulable (ESP-05)
        if (estadoActual == EstadoPedido.DESPACHADO || estadoActual == EstadoPedido.ENTREGADO) {
            throw new ConflictoException("PEDIDO_NO_ANULABLE", "El pedido se encuentra en " + estadoActual + "; corresponde iniciar una devolución (F3)");
        }
        if (estadoActual == EstadoPedido.ANULADO) {
            throw new ConflictoException("PEDIDO_NO_ANULABLE", "El pedido ya se encuentra ANULADO");
        }

        // 4. Validar que el motivo aplique al estado
        if (!motivoEnum.aplicaA(estadoActual)) {
            throw new SolicitudInvalidaException("MOTIVO_INVALIDO",
                    "El motivo %s no aplica para pedidos en estado %s".formatted(motivoEnum, estadoActual));
        }

        // 5. Determinar actor de auditoría
        ActorHistorial actor = actorSolicitante;
        if (motivoEnum == MotivoAnulacion.PAGO_NO_COMPLETADO) {
            actor = ActorHistorial.CANAL_CHATBOT;
        }

        // 6. Según el estado actual del pedido:
        if (estadoActual == EstadoPedido.CREADO || estadoActual == EstadoPedido.PAGADO) {
            // ANULACIÓN DIRECTA (200 OK)
            Anulacion a = anulaciones.findByIdPedido(pedido.getId()).orElseGet(Anulacion::new);
            a.setIdPedido(pedido.getId());
            a.setMotivo(motivoEnum.name());
            a.setIdSolicitante(pedido.getIdCliente());
            a.setEstado(EstadoAnulacion.EJECUTADA);
            a.setFecha(LocalDate.now(ZoneOffset.UTC));
            anulaciones.saveAndFlush(a);

            String anulacionCodigo = CodigoPublico.anulacion(a.getId());

            // Transicionar estado en F1 (registra historial y publica evento outbox)
            maquinaEstados.cambiar(pedido, EstadoPedido.ANULADO, actor, motivoEnum.name());

            // Efectos de F2:
            // 1. Liberar stock reservado en Productos si aplica, después del commit
            MotivoLiberacion motivoProd = (motivoEnum == MotivoAnulacion.PAGO_NO_COMPLETADO)
                    ? MotivoLiberacion.PAGO_NO_COMPLETADO : MotivoLiberacion.ANULACION;
            liberarDespuesDeCommit(pedido, motivoProd);

            // 2. Si estaba PAGADO, registrar reembolso y enviarlo a M2 (F4) después del commit
            boolean reembolsoGenerado = false;
            if (estadoActual == EstadoPedido.PAGADO) {
                reembolsos.registrar(anulacionCodigo, pedido.getId(), pedido.getTotal(), pedido.getMoneda(), motivoEnum.name());
                reembolsoGenerado = true;
                ejecutarDespuesDeCommit(() -> reembolsos.enviar(anulacionCodigo));
            }

            return SolicitarAnulacionResponse.directa(pedido.getCodigo(), EstadoPedido.ANULADO.name(), reembolsoGenerado);

        } else if (estadoActual == EstadoPedido.EN_PREPARACION) {
            // REQUIERE AUTORIZACIÓN DEL GESTOR (202 Accepted)
            Anulacion previa = anulaciones.findByIdPedido(pedido.getId()).orElse(null);
            if (previa != null) {
                if (previa.getEstado() == EstadoAnulacion.PENDIENTE_AUTORIZACION) {
                    throw new ConflictoException("SOLICITUD_DUPLICADA", "Ya existe una solicitud de anulación pendiente de autorización para este pedido");
                }
                // Si estaba RECHAZADA, se reutiliza la fila para respetar unique id_pedido
                previa.setMotivo(motivoEnum.name());
                previa.setEstado(EstadoAnulacion.PENDIENTE_AUTORIZACION);
                previa.setIdAutorizador(null);
                previa.setFecha(LocalDate.now(ZoneOffset.UTC));
                anulaciones.saveAndFlush(previa);
                String cod = CodigoPublico.anulacion(previa.getId());
                return SolicitarAnulacionResponse.pendiente(pedido.getCodigo(), EstadoPedido.EN_PREPARACION.name(), cod,
                        "Pedido en preparación; requiere aprobación administrativa");
            }

            Anulacion nueva = new Anulacion();
            nueva.setIdPedido(pedido.getId());
            nueva.setMotivo(motivoEnum.name());
            nueva.setIdSolicitante(pedido.getIdCliente());
            nueva.setEstado(EstadoAnulacion.PENDIENTE_AUTORIZACION);
            nueva.setFecha(LocalDate.now(ZoneOffset.UTC));
            anulaciones.saveAndFlush(nueva);

            String cod = CodigoPublico.anulacion(nueva.getId());
            return SolicitarAnulacionResponse.pendiente(pedido.getCodigo(), EstadoPedido.EN_PREPARACION.name(), cod,
                    "Pedido en preparación; requiere aprobación administrativa");
        }

        throw new ConflictoException("ESTADO_NO_SOPORTADO", "Estado no soportado para solicitud de anulación");
    }

    /**
     * Endpoint 1.8: GET /api/v1/pedidos/anulaciones/pendientes
     */
    @Transactional(readOnly = true)
    public ListadoAnulacionesPendientesResponse listarConFiltros(
            EstadoAnulacion estado,
            ventas.pedido.CanalVenta canal,
            String busqueda,
            int page,
            int size,
            String sort) {

        Sort ordenacion = Sort.by(Sort.Direction.DESC, "fecha").and(Sort.by(Sort.Direction.DESC, "id"));
        if (sort != null && !sort.isBlank()) {
            String[] partes = sort.split(",");
            String campo = partes[0].trim();
            if ("fechaSolicitud".equalsIgnoreCase(campo)) {
                campo = "fecha";
            }
            if ("recientes".equalsIgnoreCase(campo)) {
                ordenacion = Sort.by(Sort.Direction.DESC, "fecha").and(Sort.by(Sort.Direction.DESC, "id"));
            } else if ("antiguos".equalsIgnoreCase(campo)) {
                ordenacion = Sort.by(Sort.Direction.ASC, "fecha").and(Sort.by(Sort.Direction.ASC, "id"));
            } else {
                Sort.Direction dir = partes.length > 1 && "asc".equalsIgnoreCase(partes[1].trim())
                        ? Sort.Direction.ASC
                        : Sort.Direction.DESC;
                ordenacion = Sort.by(dir, campo).and(Sort.by(dir, "id"));
            }
        }

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size), ordenacion);
        Specification<Anulacion> spec = AnulacionesFiltro.conFiltros(estado, canal, busqueda);

        Page<Anulacion> pageResult = anulaciones.findAll(spec, pageable);

        List<AnulacionPendienteDto> items = pageResult.getContent().stream()
                .map(a -> {
                    Pedido p = a.getPedido();
                    return new AnulacionPendienteDto(
                            CodigoPublico.anulacion(a.getId()),
                            p != null ? p.getCodigo() : "PED-" + a.getIdPedido(),
                            a.getMotivo(),
                            a.getFecha(),
                            a.getEstado(),
                            p != null ? p.getCanal() : null,
                            p != null ? p.getNombreContacto() : null,
                            p != null ? p.getEstado() : null
                    );
                })
                .toList();

        return new ListadoAnulacionesPendientesResponse(
                items,
                pageResult.getNumber(),
                pageResult.getTotalPages(),
                pageResult.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public ListadoAnulacionesPendientesResponse listarPendientes(int pagina, int tamano) {
        return listarConFiltros(EstadoAnulacion.PENDIENTE_AUTORIZACION, null, null, pagina, tamano, "fecha,desc");
    }

    /**
     * Endpoint 1.9: PATCH /api/v1/pedidos/{pedidoId}/anulaciones/{anulacionId}
     */
    @Transactional(noRollbackFor = ConflictoException.class)
    public ResolverAnulacionResponse resolverAnulacion(String pedidoCodigo, String anulacionCodigo, ResolverAnulacionRequest req) {
        Long idAnulacion = CodigoPublico.id(anulacionCodigo);
        Anulacion a = anulaciones.findById(idAnulacion)
                .orElseThrow(() -> new RecursoNoEncontradoException("ANULACION_NO_ENCONTRADA", "No se encontró la solicitud " + anulacionCodigo));

        Pedido pedido = pedidos.findById(a.getIdPedido())
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO", "No se encontró el pedido asociado a la anulación"));

        if (!pedido.getCodigo().equalsIgnoreCase(pedidoCodigo)) {
            throw new SolicitudInvalidaException("DATOS_INCONSISTENTES", "La anulación indicada no pertenece al pedido " + pedidoCodigo);
        }

        if (a.getEstado() != EstadoAnulacion.PENDIENTE_AUTORIZACION) {
            throw new ConflictoException("ANULACION_NO_PENDIENTE", "La anulación no se encuentra en PENDIENTE_AUTORIZACION");
        }

        String decision = req.decision().trim().toUpperCase();
        UUID autorizadorUuid = null;
        if (req.autorizadorId() != null && !req.autorizadorId().isBlank()) {
            try {
                autorizadorUuid = UUID.fromString(req.autorizadorId());
            } catch (IllegalArgumentException ignored) {}
        }

        if ("APROBADA".equals(decision)) {
            if (pedido.getEstado() != EstadoPedido.EN_PREPARACION) {
                a.setEstado(EstadoAnulacion.RECHAZADA);
                a.setIdAutorizador(autorizadorUuid);
                anulaciones.saveAndFlush(a);
                throw new ConflictoException("PEDIDO_YA_NO_EN_PREPARACION", "El pedido ya no está en EN_PREPARACION; anulación rechazada");
            }

            // ESP-06: cancelar el despacho en G2 antes de anular; si el paquete ya salió, la anulación no procede
            if (pedido.getCanal() != CanalVenta.RETAIL) {
                Cancelacion cancelacion = despacho.cancelar(pedido.getCodigo(), a.getMotivo());
                if (cancelacion == Cancelacion.EN_CAMINO) {
                    a.setEstado(EstadoAnulacion.RECHAZADA);
                    a.setIdAutorizador(autorizadorUuid);
                    anulaciones.saveAndFlush(a);
                    throw new ConflictoException("PEDIDO_EN_CAMINO",
                            "El pedido ya salió a reparto; la anulación no procede. Si se entrega, el cliente puede pedir una devolución");
                }
                if (cancelacion == Cancelacion.FALLO) {
                    // ESP-06 CA-05: la anulación sigue, pero la falla queda visible para reintentarla a mano
                    log.error("INCONSISTENCIA: no se pudo cancelar el despacho de {} en Despacho; reintentar la cancelación",
                            pedido.getCodigo());
                }
            }

            a.setEstado(EstadoAnulacion.EJECUTADA);
            a.setIdAutorizador(autorizadorUuid);
            anulaciones.saveAndFlush(a);

            // Transicionar estado a ANULADO con GESTOR
            maquinaEstados.cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.GESTOR, a.getMotivo());

            // Efectos: liberar stock y reembolsar, después del commit
            liberarDespuesDeCommit(pedido, MotivoLiberacion.ANULACION);

            reembolsos.registrar(anulacionCodigo, pedido.getId(), pedido.getTotal(), pedido.getMoneda(), a.getMotivo());
            ejecutarDespuesDeCommit(() -> reembolsos.enviar(anulacionCodigo));

            return new ResolverAnulacionResponse(pedido.getCodigo(), EstadoPedido.ANULADO.name(), EstadoAnulacion.EJECUTADA.name());

        } else if ("RECHAZADA".equals(decision)) {
            a.setEstado(EstadoAnulacion.RECHAZADA);
            a.setIdAutorizador(autorizadorUuid);
            anulaciones.saveAndFlush(a);

            return new ResolverAnulacionResponse(pedido.getCodigo(), EstadoPedido.EN_PREPARACION.name(), EstadoAnulacion.RECHAZADA.name());
        }

        throw new SolicitudInvalidaException("DECISION_INVALIDA", "La decisión debe ser APROBADA o RECHAZADA");
    }

    /** RETAIL nunca reserva stock (acuerdo G4, R9); sin reservaId todavía, la libera ResultadoReserva al llegar. */
    private void liberarDespuesDeCommit(Pedido pedido, MotivoLiberacion motivo) {
        if (pedido.getCanal() != CanalVenta.RETAIL && pedido.getReservaId() != null) {
            String codigo = pedido.getCodigo();
            String reservaId = pedido.getReservaId();
            ejecutarDespuesDeCommit(() -> productos.liberar(codigo, reservaId, motivo));
        }
    }

    private void ejecutarDespuesDeCommit(Runnable accion) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    accion.run();
                }
            });
        } else {
            accion.run();
        }
    }
}

package postventa.reembolso;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import postventa.compartido.*;
import postventa.dashboard.ProyeccionesService;
import postventa.devolucion.Devolucion;
import postventa.devolucion.DevolucionRepository;
import postventa.devolucion.EstadoDevolucion;
import postventa.devolucion.TipoResolucion;
import postventa.integracion.*;
import postventa.reembolso.dto.*;

@Service
public class ReembolsoService {
    private final ReembolsoRepository repositorio; private final VentasClient ventas; private final PasarelaClient pasarela; private final DevolucionRepository devoluciones; private final ProyeccionesService proyecciones; private final Clock reloj; private final TransactionOperations transacciones;
    @Autowired
    public ReembolsoService(ReembolsoRepository r, VentasClient v, PasarelaClient p, DevolucionRepository d,
                            ProyeccionesService proyecciones, PlatformTransactionManager gestorTransacciones) { this(r, v, p, d, proyecciones, Clock.systemUTC(), new TransactionTemplate(gestorTransacciones)); }
    ReembolsoService(ReembolsoRepository r, VentasClient v, PasarelaClient p, DevolucionRepository d,
                     ProyeccionesService proyecciones, Clock c) { this(r, v, p, d, proyecciones, c, sinTransaccion()); }
    ReembolsoService(ReembolsoRepository r, VentasClient v, PasarelaClient p, DevolucionRepository d,
                     ProyeccionesService proyecciones, Clock c, TransactionOperations t) { repositorio=r; ventas=v; pasarela=p; devoluciones=d; this.proyecciones=proyecciones; reloj=c; transacciones=t; }
    public java.util.Optional<ProcesarReembolsoResponse> consultarPorOrigen(OrigenReembolso origen, long origenId) {
        return repositorio.findByTipoOrigenAndIdOrigen(origen, origenId).map(this::respuesta);
    }
    public ProcesarReembolsoResponse procesar(String clave, ProcesarReembolsoRequest s) {
        Preparacion preparacion = transacciones.execute(estado -> preparar(clave, s));
        if (preparacion.respuesta() != null) return preparacion.respuesta();
        PasarelaClient.ResultadoExtorno resultado;
        try {
            resultado = pasarela.solicitarExtorno(preparacion.clavePasarela(), preparacion.reembolso().codigo(),
                    preparacion.reembolso().getMonto(), preparacion.reembolso().getMoneda());
        } catch (RuntimeException e) {
            resultado = new PasarelaClient.ResultadoExtorno(EstadoReembolso.FALLIDO, null,
                    "No se pudo comunicar con la pasarela: " + e.getClass().getSimpleName());
        }
        PasarelaClient.ResultadoExtorno finalResultado = resultado;
        return transacciones.execute(estado -> registrarResultado(preparacion.reembolso(), preparacion.canal(), finalResultado));
    }
    private Preparacion preparar(String clave, ProcesarReembolsoRequest s) {
        if (clave == null || clave.isBlank()) throw new SolicitudInvalidaException("CLAVE_IDEMPOTENCIA_REQUERIDA", "X-Idempotency-Key es obligatoria");
        if (clave.length() > 100) throw new SolicitudInvalidaException("CLAVE_IDEMPOTENCIA_INVALIDA", "X-Idempotency-Key no puede superar 100 caracteres");
        validarMonto(s.monto());
        validarPrefijoOrigen(s.origen(), s.origenId());
        long pedidoId=CodigoPublico.id(s.referenciaId()); long origenId=CodigoPublico.id(s.origenId());
        repositorio.bloquearSaldoPedido(pedidoId);
        List<Reembolso> reembolsosPedido = repositorio.bloquearReembolsosDelPedido(pedidoId);
        Reembolso existente = repositorio.findByIdempotencyKey(clave).orElse(null);
        if (existente != null) {
            if (existente.getTipoOrigen() != s.origen()
                    || !existente.getIdOrigen().equals(CodigoPublico.id(s.origenId()))
                    || !existente.getIdPedido().equals(CodigoPublico.id(s.referenciaId()))
                    || existente.getMonto().compareTo(s.monto()) != 0
                    || !existente.getMoneda().equals(s.moneda()))
                throw new ConflictoException("CLAVE_IDEMPOTENCIA_EN_CONFLICTO", "La clave ya fue usada con datos distintos");
            if (existente.getEstado()==EstadoReembolso.EXITOSO)
                return Preparacion.respondida(respuesta(existente));
            if (existente.getEstado()==EstadoReembolso.PENDIENTE && existente.getIntentos().isEmpty())
                return Preparacion.respondida(respuesta(existente));
            // Un PENDIENTE con intento registrado corresponde a un timeout: se repite la misma clave
            // de pasarela para recuperar el resultado idempotente que el simulador pudo haber guardado.
            PedidoResumen pedido = validarSaldoParaReintento(s.referenciaId(), existente.getMoneda(), reembolsosPedido);
            return Preparacion.paraEjecutar(existente, clavePasarela(existente), pedido.canal());
        }
        if (repositorio.findByTipoOrigenAndIdOrigen(s.origen(), origenId).isPresent())
            throw new ConflictoException("ORIGEN_YA_REEMBOLSADO", "El origen ya tiene un reembolso registrado");
        PedidoResumen pedido=consultarPagoConfirmado(s.referenciaId(), s.moneda());
        validarOrigen(s, origenId, pedidoId);
        BigDecimal comprometido=saldoComprometido(reembolsosPedido);
        if (comprometido.add(s.monto()).compareTo(pedido.total())>0) throw new ConflictoException("SALDO_INSUFICIENTE", "El monto supera el saldo reembolsable");
        Reembolso r=new Reembolso(); r.setIdPedido(pedidoId); r.setIdOrigen(origenId); r.setTipoOrigen(s.origen()); r.setIdempotencyKey(clave); r.setMonto(s.monto()); r.setMoneda(s.moneda()); r.setEstado(EstadoReembolso.PENDIENTE); r.setIdAutorizador(autorizador(s.solicitadoPor())); r.setFecha(OffsetDateTime.now(reloj).toLocalDate());
        Reembolso guardado = repositorio.save(r);
        return Preparacion.paraEjecutar(guardado, clavePasarela(guardado), pedido.canal());
    }
    private static void validarMonto(BigDecimal monto) {
        if (monto == null || monto.signum() <= 0 || monto.scale() > 2)
            throw new SolicitudInvalidaException("MONTO_INVALIDO", "El monto debe ser positivo y tener como máximo dos decimales");
    }
    private static void validarPrefijoOrigen(OrigenReembolso origen, String origenId) {
        String prefijo = origen == OrigenReembolso.ANULACION ? "ANUL-"
                : origen == OrigenReembolso.DEVOLUCION ? "DEV-" : null;
        if (prefijo == null || origenId == null || !origenId.trim().startsWith(prefijo))
            throw new SolicitudInvalidaException("ORIGEN_NO_ELEGIBLE",
                    "El código del origen no corresponde al tipo de reembolso");
    }
    private void validarOrigen(ProcesarReembolsoRequest solicitud, long origenId, long pedidoId) {
        if (solicitud.origen() == OrigenReembolso.ANULACION) {
            // M1 invoca F4 después de concluir su propia transacción de anulación.
            // El pago y la moneda ya se validan en consultarPagoConfirmado().
            return;
        }
        Devolucion devolucion = devoluciones.findById(origenId)
                .filter(d -> d.codigo().equals(solicitud.origenId().trim()))
                .filter(d -> d.getIdPedido().equals(pedidoId))
                .filter(d -> (d.getEstado() == EstadoDevolucion.APROBADA || d.getEstado() == EstadoDevolucion.COMPLETADA)
                        && d.getResolucion() == TipoResolucion.REEMBOLSO)
                .orElseThrow(() -> new SolicitudInvalidaException("ORIGEN_NO_ELEGIBLE",
                        "La devolución no está aprobada para reembolso o no pertenece al pedido indicado"));
    }
    private PedidoResumen validarSaldoParaReintento(String referenciaId, String moneda, List<Reembolso> reembolsosPedido) {
        PedidoResumen pedido = consultarPagoConfirmado(referenciaId, moneda);
        if (saldoComprometido(reembolsosPedido).compareTo(pedido.total()) > 0)
            throw new ConflictoException("SALDO_INSUFICIENTE", "El monto comprometido supera el saldo reembolsable");
        return pedido;
    }
    private static BigDecimal saldoComprometido(List<Reembolso> reembolsos) {
        return reembolsos.stream()
                .filter(r -> r.getEstado() == EstadoReembolso.EXITOSO || r.getEstado() == EstadoReembolso.PENDIENTE)
                .map(Reembolso::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private PedidoResumen consultarPagoConfirmado(String referenciaId, String moneda) {
        PedidoResumen pedido=ventas.consultarPedido(referenciaId).orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO", "No existe el pedido"));
        if (!pedido.pagoConfirmado() || pedido.total()==null || pedido.moneda()==null || !pedido.moneda().equals(moneda)) throw new SolicitudInvalidaException("PAGO_INVALIDO", "El pago original no está confirmado o no coincide con el reembolso");
        return pedido;
    }
    private static UUID autorizador(String solicitadoPor) {
        if (solicitadoPor == null || solicitadoPor.isBlank()) return null;
        try {
            return UUID.fromString(solicitadoPor.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    private ProcesarReembolsoResponse registrarResultado(Reembolso r, CanalVenta canal, PasarelaClient.ResultadoExtorno x) {
        OffsetDateTime fecha = ahora();
        r.setEstado(x.estado());
        r.setIdTransaccion(x.transaccionId());
        r.agregarIntento(x.estado(), x.transaccionId(), x.detalle(), fecha);
        Reembolso guardado = repositorio.save(r);
        if (x.estado() == EstadoReembolso.EXITOSO) {
            proyecciones.reembolsoExitoso(fecha, canal, guardado.getMonto(), guardado.getMoneda());
        }
        return respuesta(guardado);
    }
    private static String clavePasarela(Reembolso r) {
        if (r.getIntentos().isEmpty())
            return r.getIdempotencyKey();
        int ultimoResultadoDefinitivo = r.getIntentos().stream()
                .filter(intento -> intento.getEstado() != EstadoReembolso.PENDIENTE)
                .mapToInt(ReembolsoIntento::getNumeroIntento)
                .max()
                .orElse(0);
        int numeroIntentoPasarela = ultimoResultadoDefinitivo + 1;
        return numeroIntentoPasarela == 1 ? r.getIdempotencyKey()
                : r.getIdempotencyKey()+"-"+numeroIntentoPasarela;
    }
    private ProcesarReembolsoResponse respuesta(Reembolso r) { return new ProcesarReembolsoResponse(r.codigo(),r.getIdTransaccion(),r.getEstado(),r.getMonto(),r.getMoneda(),r.getIntentos().isEmpty() ? ahora() : r.getIntentos().get(r.getIntentos().size()-1).getFechaHora()); }
    private OffsetDateTime ahora() { return OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC); }
    private static TransactionOperations sinTransaccion() { return new TransactionOperations() { @Override public <T> T execute(TransactionCallback<T> accion) { return accion.doInTransaction(null); } }; }
    private record Preparacion(Reembolso reembolso, String clavePasarela, CanalVenta canal, ProcesarReembolsoResponse respuesta) {
        static Preparacion paraEjecutar(Reembolso reembolso, String clavePasarela, CanalVenta canal) { return new Preparacion(reembolso, clavePasarela, canal, null); }
        static Preparacion respondida(ProcesarReembolsoResponse respuesta) { return new Preparacion(null, null, null, respuesta); }
    }
}

package postventa.reembolso;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import postventa.compartido.*;
import postventa.integracion.*;
import postventa.reembolso.dto.*;

@Service
public class ReembolsoService {
    private final ReembolsoRepository repositorio; private final VentasClient ventas; private final PasarelaClient pasarela; private final Clock reloj;
    @Autowired
    public ReembolsoService(ReembolsoRepository r, VentasClient v, PasarelaClient p) { this(r,v,p,Clock.systemUTC()); }
    ReembolsoService(ReembolsoRepository r, VentasClient v, PasarelaClient p, Clock c) { repositorio=r; ventas=v; pasarela=p; reloj=c; }
    @Transactional
    public ProcesarReembolsoResponse procesar(String clave, ProcesarReembolsoRequest s) {
        if (clave == null || clave.isBlank()) throw new SolicitudInvalidaException("CLAVE_IDEMPOTENCIA_REQUERIDA", "X-Idempotency-Key es obligatoria");
        Reembolso existente = repositorio.findByIdempotencyKey(clave).orElse(null);
        if (existente != null) {
            if (existente.getTipoOrigen() != s.origen()
                    || !existente.getIdOrigen().equals(CodigoPublico.id(s.origenId()))
                    || !existente.getIdPedido().equals(CodigoPublico.id(s.referenciaId()))
                    || existente.getMonto().compareTo(s.monto()) != 0
                    || !existente.getMoneda().equals(s.moneda()))
                throw new ConflictoException("CLAVE_IDEMPOTENCIA_EN_CONFLICTO", "La clave ya fue usada con datos distintos");
            if (existente.getEstado()==EstadoReembolso.EXITOSO) return respuesta(existente);
            return ejecutar(existente);
        }
        long pedidoId=CodigoPublico.id(s.referenciaId()); long origenId=CodigoPublico.id(s.origenId());
        PedidoResumen pedido=ventas.consultarPedido(s.referenciaId()).orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO", "No existe el pedido"));
        if (pedido.total()==null || pedido.moneda()==null || !pedido.moneda().equals(s.moneda())) throw new SolicitudInvalidaException("PAGO_INVALIDO", "El pago original no coincide con el reembolso");
        BigDecimal comprometido=repositorio.saldoComprometido(pedidoId);
        if (comprometido.add(s.monto()).compareTo(pedido.total())>0) throw new ConflictoException("SALDO_INSUFICIENTE", "El monto supera el saldo reembolsable");
        Reembolso r=new Reembolso(); r.setIdPedido(pedidoId); r.setIdOrigen(origenId); r.setTipoOrigen(s.origen()); r.setIdempotencyKey(clave); r.setMonto(s.monto()); r.setMoneda(s.moneda()); r.setEstado(EstadoReembolso.PENDIENTE); r.setFecha(OffsetDateTime.now(reloj).toLocalDate());
        return ejecutar(repositorio.save(r));
    }
    private ProcesarReembolsoResponse ejecutar(Reembolso r) { PasarelaClient.ResultadoExtorno x=pasarela.solicitarExtorno(r.getIdempotencyKey(),r.codigo(),r.getMonto(),r.getMoneda()); r.setEstado(x.estado()); r.setIdTransaccion(x.transaccionId()); r.agregarIntento(x.estado(),x.transaccionId(),x.detalle()); return respuesta(r); }
    private ProcesarReembolsoResponse respuesta(Reembolso r) { return new ProcesarReembolsoResponse(r.codigo(),r.getIdTransaccion(),r.getEstado(),r.getMonto(),r.getMoneda(),OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC)); }
}

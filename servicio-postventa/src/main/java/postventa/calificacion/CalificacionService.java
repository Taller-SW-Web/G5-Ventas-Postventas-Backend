package postventa.calificacion;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import postventa.calificacion.dto.RegistrarCsatRequest;
import postventa.compartido.CodigoPublico;
import postventa.compartido.ConflictoException;
import postventa.compartido.RecursoNoEncontradoException;
import postventa.compartido.SolicitudInvalidaException;
import postventa.integracion.PedidoResumen;
import postventa.integracion.VentasClient;

/**
 * Registra una calificación y actualiza sus métricas de manera atómica.
 */
@Service
public class CalificacionService {

    private final CalificacionRepository calificaciones;
    private final MetricasCsatRepository metricas;
    private final VentasClient ventas;
    private final TransactionTemplate transacciones;

    public CalificacionService(
            CalificacionRepository calificaciones,
            MetricasCsatRepository metricas,
            VentasClient ventas,
            PlatformTransactionManager gestorTransacciones) {

        this.calificaciones = calificaciones;
        this.metricas = metricas;
        this.ventas = ventas;
        this.transacciones = new TransactionTemplate(
                Objects.requireNonNull(
                        gestorTransacciones,
                        "El gestor de transacciones es obligatorio"));
    }

    /**
     * clienteAutenticado debe proceder del JWT, no del cuerpo de la solicitud.
     */
    public void registrar(
            RegistrarCsatRequest solicitud,
            UUID clienteAutenticado) {

        if (clienteAutenticado == null
                || !clienteAutenticado.equals(solicitud.clienteId())) {
            throw new AccessDeniedException(
                    "El cliente indicado no coincide con el cliente autenticado");
        }

        Integer puntuacion = solicitud.puntuacion();

        if (puntuacion == null || puntuacion < 1 || puntuacion > 5) {
            throw new SolicitudInvalidaException(
                    "PUNTUACION_INVALIDA",
                    "La puntuación debe estar entre 1 y 5");
        }

        PedidoResumen pedido = ventas.consultarPedido(solicitud.pedidoId())
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido indicado"));

        if (!clienteAutenticado.equals(pedido.clienteId())) {
            throw new AccessDeniedException(
                    "El pedido no pertenece al cliente autenticado");
        }

        if (!pedido.entregado()) {
            throw new PedidoNoEntregadoException();
        }

        long idPedido = CodigoPublico.id(pedido.pedidoId());
        LocalDate fecha = LocalDate.now(ZoneOffset.UTC);

        try {
            transacciones.executeWithoutResult(estado -> {
                if (calificaciones.existsByIdPedido(idPedido)) {
                    throw duplicada();
                }

                Calificacion calificacion = new Calificacion();
                calificacion.setIdPedido(idPedido);
                calificacion.setPuntaje(puntuacion);
                calificacion.setComentario(solicitud.comentario());
                calificacion.setCanal(solicitud.canal());
                calificacion.setFecha(fecha);

                calificaciones.saveAndFlush(calificacion);

                metricas.incrementar(
                        fecha,
                        solicitud.canal(),
                        puntuacion);
            });
        } catch (DataIntegrityViolationException error) {
            // La transacción ya terminó y revirtió antes de esta consulta.
            // Reconoce una solicitud simultánea que registró primero el pedido.
            if (calificaciones.existsByIdPedido(idPedido)) {
                throw duplicada();
            }

            throw error;
        }
    }

    private static ConflictoException duplicada() {
        return new ConflictoException(
                "CSAT_ALREADY_SUBMITTED",
                "El pedido ya tiene una calificación registrada");
    }
}

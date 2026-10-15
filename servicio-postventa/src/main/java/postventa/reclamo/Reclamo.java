package postventa.reclamo;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import postventa.compartido.CanalVenta;
import postventa.compartido.CodigoPublico;

/**
 * Tabla postventa.reclamo (con las columnas de H5). codigo (REC-AAAA-NNNN) se guarda: primero un código
 * temporal y, con el id ya asignado, el definitivo en la misma transacción (guía 3.5).
 * id_pedido es el número del código del pedido de M1, sin FK física (RNF-07).
 */
@Entity
@Table(name = "reclamo", schema = "postventa")
@Getter
@Setter
@NoArgsConstructor
public class Reclamo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String codigo;

    @Column(name = "id_pedido")
    private Long idPedido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoReclamo tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MotivoReclamo motivo;

    @Column(nullable = false, columnDefinition = "text")
    private String detalle;

    @Column(name = "nombre_consumidor", nullable = false, length = 150)
    private String nombreConsumidor;

    @Column(nullable = false, length = 20)
    private String documento;

    @Column(length = 150)
    private String email;

    @Column(length = 20)
    private String telefono;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoReclamo estado;

    @Column(name = "plazo_dias_habiles", nullable = false)
    private Integer plazoDiasHabiles;

    @Column(name = "fecha_limite_respuesta", nullable = false)
    private LocalDate fechaLimiteRespuesta;

    @Column(name = "respuesta_visible_cliente", columnDefinition = "text")
    private String respuestaVisibleCliente;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CanalVenta canal;

    /** UUID (sub) del cliente, cuando el reclamo lo registró el propio cliente con su token. */
    @Column(name = "id_cliente")
    private UUID idCliente;

    @Column(name = "fecha_registro", nullable = false)
    private OffsetDateTime fechaRegistro;

    /** Hora UTC de la respuesta formal (ATENDIDO o DERIVADO); evidencia del SLA (ESP-14). */
    @Column(name = "fecha_atencion")
    private OffsetDateTime fechaAtencion;

    @Column(name = "atendido_por", length = 50)
    private String atendidoPor;

    /** Código del pedido de M1, o null en una queja general. */
    public String codigoPedido() {
        return idPedido == null ? null : CodigoPublico.pedido(idPedido, fechaRegistro.getYear());
    }
}

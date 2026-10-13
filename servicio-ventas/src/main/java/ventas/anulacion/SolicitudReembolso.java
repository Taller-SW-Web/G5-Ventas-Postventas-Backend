package ventas.anulacion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Tabla ventas.solicitud_reembolso: el reembolso que M1 le debe pedir a M2 por una anulación de un pedido
 * ya cobrado. Se guarda en la misma transacción que la anulación, así no se pierde si M2 está caído.
 */
@Entity
@Table(name = "solicitud_reembolso", schema = "ventas")
@Getter
@Setter
@NoArgsConstructor
public class SolicitudReembolso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_anulacion", nullable = false, unique = true)
    private Long idAnulacion;

    @Column(name = "id_pedido", nullable = false)
    private Long idPedido;

    @Column(nullable = false)
    private BigDecimal monto;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String moneda;

    /** Texto que se manda a M2 en el campo motivo. */
    @Column(nullable = false)
    private String motivo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoSolicitudReembolso estado;

    /** REEM-NNNNN que devuelve M2 al aceptarla. */
    @Column(name = "reembolso_id")
    private String reembolsoId;

    @Column(nullable = false)
    private int intentos;

    @Column(name = "ultimo_error")
    private String ultimoError;

    @Column(name = "creado_en", nullable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private OffsetDateTime actualizadoEn;
}

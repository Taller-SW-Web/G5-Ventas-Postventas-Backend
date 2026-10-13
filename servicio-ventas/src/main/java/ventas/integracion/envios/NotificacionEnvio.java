package ventas.integracion.envios;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Tabla ventas.notificacion_envio: avisos pedidos.envio.estado_actualizado para Marketplace (1.10, acuerdo H-05).
 * Se guardan en la misma transacción que el cambio de estado y se envían después, así no se pierden si
 * Marketplace está caído. Es independiente de outbox_evento (ese es solo para M2).
 */
@Entity
@Table(name = "notificacion_envio", schema = "ventas")
@Getter
@Setter
@NoArgsConstructor
public class NotificacionEnvio {

    /** Viaja como eventId: Marketplace deduplica con él si M1 reintenta. */
    @Id
    @Column(name = "id_evento")
    private UUID idEvento;

    @Column(name = "id_pedido", nullable = false)
    private Long idPedido;

    @Column(name = "estado_publico", nullable = false)
    private String estadoPublico;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "creado_en", nullable = false)
    private OffsetDateTime creadoEn;

    /** null mientras no se haya enviado a Marketplace. */
    @Column(name = "enviado_en")
    private OffsetDateTime enviadoEn;

    @Column(nullable = false)
    private int intentos;
}

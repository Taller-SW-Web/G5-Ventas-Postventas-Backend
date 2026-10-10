package ventas.integracion.eventos;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_evento", schema = "ventas")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_evento", nullable = false, unique = true)
    private UUID idEvento;

    @Convert(converter = TipoEventoConverter.class)
    @Column(nullable = false)
    private TipoEvento tipo;

    @Column(name = "id_pedido")
    private Long idPedido;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "creado_en", nullable = false)
    private OffsetDateTime creadoEn;

    /** null mientras no se haya enviado a M2 */
    @Column(name = "enviado_en")
    private OffsetDateTime enviadoEn;

    @Column(nullable = false)
    private int intentos;
}

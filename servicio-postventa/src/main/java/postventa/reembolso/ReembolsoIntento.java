package postventa.reembolso;

import java.time.OffsetDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Bitácora inmutable de llamadas a la pasarela (ESP-10). */
@Entity
@Table(name = "reembolso_intento", schema = "postventa")
@Getter @Setter @NoArgsConstructor
public class ReembolsoIntento {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "id_reembolso", nullable = false) private Reembolso reembolso;
    @Column(name = "numero_intento", nullable = false) private Integer numeroIntento;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private EstadoReembolso estado;
    @Column(name = "id_transaccion") private String idTransaccion;
    @Column(columnDefinition = "text") private String resultado;
    @Column(name = "fecha_hora", nullable = false) private OffsetDateTime fechaHora = OffsetDateTime.now();
}

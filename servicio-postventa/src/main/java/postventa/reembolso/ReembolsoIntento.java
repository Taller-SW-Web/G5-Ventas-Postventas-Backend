package postventa.reembolso;

import java.time.OffsetDateTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Bitácora inmutable de llamadas a la pasarela (ESP-10). */
@Entity
@Table(name = "reembolso_intento", schema = "postventa")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReembolsoIntento {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "id_reembolso", nullable = false) private Reembolso reembolso;
    @Column(name = "numero_intento", nullable = false) private Integer numeroIntento;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private EstadoReembolso estado;
    @Column(name = "id_transaccion") private String idTransaccion;
    @Column(columnDefinition = "text") private String resultado;
    @Column(name = "fecha_hora", nullable = false) private OffsetDateTime fechaHora = OffsetDateTime.now();

    ReembolsoIntento(Reembolso reembolso, int numeroIntento, EstadoReembolso estado, String idTransaccion,
                     String resultado, OffsetDateTime fechaHora) {
        this.reembolso = reembolso;
        this.numeroIntento = numeroIntento;
        this.estado = estado;
        this.idTransaccion = idTransaccion;
        this.resultado = resultado;
        this.fechaHora = fechaHora;
    }
}

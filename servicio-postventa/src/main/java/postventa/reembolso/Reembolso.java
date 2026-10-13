package postventa.reembolso;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import postventa.compartido.CodigoPublico;

/** Operación monetaria de F4. No hay FK hacia M1 (RNF-07). */
@Entity
@Table(name = "reembolso", schema = "postventa")
@Getter @Setter @NoArgsConstructor
public class Reembolso {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "id_origen", nullable = false) private Long idOrigen;
    @Enumerated(EnumType.STRING) @Column(name = "tipo_origen", nullable = false) private OrigenReembolso tipoOrigen;
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100) private String idempotencyKey;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal monto;
    @Column(nullable = false, length = 3) private String moneda;
    @Column(name = "id_transaccion", length = 100) private String idTransaccion;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private EstadoReembolso estado;
    @Column(nullable = false) private LocalDate fecha;
    @Column(name = "id_pedido", nullable = false) private Long idPedido;
    @OneToMany(mappedBy = "reembolso", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("numeroIntento") private List<ReembolsoIntento> intentos = new ArrayList<>();
    public String codigo() { return CodigoPublico.reembolso(id); }
    public void agregarIntento(EstadoReembolso estado, String transaccion, String resultado) {
        ReembolsoIntento intento = new ReembolsoIntento();
        intento.setReembolso(this); intento.setNumeroIntento(intentos.size() + 1); intento.setEstado(estado);
        intento.setIdTransaccion(transaccion); intento.setResultado(resultado); intentos.add(intento);
    }
}

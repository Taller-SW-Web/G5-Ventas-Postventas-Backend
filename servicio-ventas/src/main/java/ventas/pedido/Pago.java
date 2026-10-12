package ventas.pedido;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pago", schema = "ventas")
@Getter
@Setter
@NoArgsConstructor
public class Pago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_pedido", nullable = false)
    private Pedido pedido;

    @Column(nullable = false)
    private String metodo;

    @Column(nullable = false)
    private BigDecimal monto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoPago estado;

    /** transaccionId de la pasarela */
    private String referencia;

    @Column(name = "fecha_proceso")
    private OffsetDateTime fechaProceso;

    /** Solo RETAIL con MIXTO: parte pagada en efectivo (acuerdo G4, R3). */
    @Column(name = "monto_efectivo")
    private BigDecimal montoEfectivo;

    /** Solo RETAIL con MIXTO: parte pagada con tarjeta en el POS. */
    @Column(name = "monto_tarjeta")
    private BigDecimal montoTarjeta;

    /** Solo RETAIL sin conexión: fecha real de la venta, hasta 72 h atrás (acuerdo G4, R8). */
    @Column(name = "fecha_venta")
    private OffsetDateTime fechaVenta;
}

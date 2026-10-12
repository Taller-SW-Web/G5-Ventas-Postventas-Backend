package ventas.anulacion;

import java.time.LocalDate;
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

/** Tabla ventas.anulacion. Un pedido tiene como máximo una (id_pedido es único). */
@Entity
@Table(name = "anulacion", schema = "ventas")
@Getter
@Setter
@NoArgsConstructor
public class Anulacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_pedido", nullable = false, unique = true)
    private Long idPedido;

    @Column(nullable = false)
    private String motivo;

    /** UUID de Seguridad de quien la pidió; null si la disparó un sistema. */
    @Column(name = "id_solicitante")
    private UUID idSolicitante;

    /** UUID del Gestor que la aprobó (solo desde EN_PREPARACION). */
    @Column(name = "id_autorizador")
    private UUID idAutorizador;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoAnulacion estado;

    @Column(nullable = false)
    private LocalDate fecha;
}

package postventa.calificacion;

import java.time.LocalDate;

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

/**
 * Calificación CSAT de un pedido entregado.
 * Referencia el pedido de M1 sin establecer una relación entre esquemas.
 */
@Entity
@Table(name = "calificacion", schema = "postventa")
@Getter
@Setter
@NoArgsConstructor
public class Calificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_pedido", nullable = false, unique = true)
    private Long idPedido;

    @Column(nullable = false)
    private Integer puntaje;

    @Column(columnDefinition = "text")
    private String comentario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CanalVenta canal;

    @Column(nullable = false)
    private LocalDate fecha;
}

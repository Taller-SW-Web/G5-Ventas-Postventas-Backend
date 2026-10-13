package postventa.devolucion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Tabla postventa.devolucion_item (H2). id_producto es texto, tal cual lo da Productos (varchar(64), igual que M1). */
@Entity
@Table(name = "devolucion_item", schema = "postventa")
@Getter
@Setter
@NoArgsConstructor
public class DevolucionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_devolucion", nullable = false)
    private Devolucion devolucion;

    @Column(name = "id_producto", nullable = false, length = 64)
    private String idProducto;

    @Column(nullable = false)
    private Integer cantidad;
}

package postventa.devolucion;

import java.time.OffsetDateTime;
import java.util.UUID;

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

/**
 * Tabla postventa.devolucion_recepcion (2.5b, ESP-08 8.2b): una fila por línea de una confirmación de recepción
 * física. Las líneas de una misma confirmación comparten operation_id, que es el del reintegro en Productos.
 * Una recepción parcial posterior es otra confirmación con otro operation_id (ESP-08 regla 15, G6 P-05).
 */
@Entity
@Table(name = "devolucion_recepcion", schema = "postventa")
@Getter
@Setter
@NoArgsConstructor
public class RecepcionDevolucion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_devolucion", nullable = false)
    private Devolucion devolucion;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(nullable = false, length = 50)
    private String sku;

    @Column(name = "id_producto", nullable = false, length = 64)
    private String idProducto;

    @Column(name = "cantidad_recibida", nullable = false)
    private Integer cantidadRecibida;

    /** Solo estas unidades se reintegran (ESP-08 regla 15). */
    @Column(name = "cantidad_apta", nullable = false)
    private Integer cantidadApta;

    @Enumerated(EnumType.STRING)
    @Column(name = "reintegro_estado", nullable = false, length = 20)
    private EstadoReintegro reintegroEstado;

    @Column(length = 500)
    private String evidencia;

    /** sub de quien recibió (Gestor o almacén, rol ADMIN_VENTAS). */
    @Column(name = "id_actor", length = 60)
    private String idActor;

    @Column(name = "fecha_hora", nullable = false)
    private OffsetDateTime fechaHora;
}

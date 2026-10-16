package postventa.devolucion;

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

/**
 * Tabla postventa.historial_devolucion (RNF-03): un registro por cada cambio de estado del expediente.
 * Nunca se edita ni se borra. estado_anterior es null en el registro inicial (SOLICITADA).
 */
@Entity
@Table(name = "historial_devolucion", schema = "postventa")
@Getter
@Setter
@NoArgsConstructor
public class HistorialDevolucion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_devolucion", nullable = false)
    private Devolucion devolucion;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_anterior", length = 20)
    private EstadoDevolucion estadoAnterior;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_nuevo", nullable = false, length = 20)
    private EstadoDevolucion estadoNuevo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActorDevolucion actor;

    /** sub del cliente o del Gestor, o el módulo del canal (modulo-marketplace). */
    @Column(name = "id_actor", length = 60)
    private String idActor;

    @Column(columnDefinition = "text")
    private String detalle;

    @Column(name = "fecha_hora", nullable = false)
    private OffsetDateTime fechaHora;

    static HistorialDevolucion de(Devolucion devolucion, EstadoDevolucion anterior, EstadoDevolucion nuevo,
                                  ActorDevolucion actor, String idActor, String detalle, OffsetDateTime fechaHora) {
        HistorialDevolucion h = new HistorialDevolucion();
        h.setDevolucion(devolucion);
        h.setEstadoAnterior(anterior);
        h.setEstadoNuevo(nuevo);
        h.setActor(actor);
        h.setIdActor(idActor);
        h.setDetalle(detalle);
        h.setFechaHora(fechaHora);
        return h;
    }
}

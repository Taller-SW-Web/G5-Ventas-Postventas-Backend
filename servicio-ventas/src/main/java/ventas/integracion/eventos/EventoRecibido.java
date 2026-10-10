package ventas.integracion.eventos;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Notificación externa ya procesada. Sirve para la idempotencia de 1.2 y 1.6:
 * si el mismo idEvento llega dos veces desde el mismo origen, la segunda no cambia nada.
 */
@Entity
@Table(name = "evento_recibido", schema = "ventas")
@Getter
@Setter
@NoArgsConstructor
public class EventoRecibido {

    /** Clave primaria: transaccionId de la pasarela, o guiaRemision:evento de logística */
    @Id
    @Column(name = "id_evento")
    private String idEvento;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrigenEvento origen;

    @Column(name = "recibido_en", nullable = false)
    private OffsetDateTime recibidoEn;

    public EventoRecibido(String idEvento, OrigenEvento origen, OffsetDateTime recibidoEn) {
        this.idEvento = idEvento;
        this.origen = origen;
        this.recibidoEn = recibidoEn;
    }
}

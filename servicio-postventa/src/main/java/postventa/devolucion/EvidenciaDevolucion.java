package postventa.devolucion;

import java.time.LocalDate;

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

/** Tabla postventa.evidencia_devolucion: la URL que devolvió 2.1 al subir el archivo. */
@Entity
@Table(name = "evidencia_devolucion", schema = "postventa")
@Getter
@Setter
@NoArgsConstructor
public class EvidenciaDevolucion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_devolucion", nullable = false)
    private Devolucion devolucion;

    @Column(nullable = false, length = 500)
    private String url;

    @Enumerated(EnumType.STRING)
    private TipoEvidencia tipo;

    @Column(nullable = false)
    private LocalDate fecha;
}

package postventa.devolucion;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import postventa.compartido.CodigoPublico;

/**
 * Tabla postventa.devolucion (con las columnas de H2 y H11). id_pedido es el número del código del pedido de M1,
 * sin FK física (RNF-07). El código público DEV-AAAA-NNNN no se guarda: se arma con id y año de fecha.
 */
@Entity
@Table(name = "devolucion", schema = "postventa")
@Getter
@Setter
@NoArgsConstructor
public class Devolucion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_pedido", nullable = false)
    private Long idPedido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoDevolucion tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MotivoDevolucion motivo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoDevolucion estado;

    @Enumerated(EnumType.STRING)
    private TipoResolucion resolucion;

    /** UUID (sub) del Gestor que dictaminó. */
    @Column(name = "id_autorizador")
    private UUID idAutorizador;

    @Column(name = "fundamento_rechazo", columnDefinition = "text")
    private String fundamentoRechazo;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(columnDefinition = "text")
    private String descripcion;

    /** UUID (sub) del cliente titular del pedido. */
    @Column(name = "id_cliente")
    private UUID idCliente;

    /** Hora real del registro (H11, ESP-07 8.3). fecha guarda solo el día. */
    @Column(name = "fecha_registro", nullable = false)
    private OffsetDateTime fechaRegistro;

    /** Hora del dictamen del Gestor (H11, ESP-08 8.3). */
    @Column(name = "fecha_resolucion")
    private OffsetDateTime fechaResolucion;

    /** reembolsoId de F4 o referencia de Despacho, según la resolución (H11, ESP-08 8.3). */
    @Column(name = "referencia_operacion", length = 100)
    private String referenciaOperacion;

    @OneToMany(mappedBy = "devolucion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<DevolucionItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "devolucion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<EvidenciaDevolucion> evidencias = new ArrayList<>();

    public void agregarItem(long idProducto, int cantidad) {
        DevolucionItem item = new DevolucionItem();
        item.setDevolucion(this);
        item.setIdProducto(idProducto);
        item.setCantidad(cantidad);
        items.add(item);
    }

    public void agregarEvidencia(String url, TipoEvidencia tipo) {
        EvidenciaDevolucion evidencia = new EvidenciaDevolucion();
        evidencia.setDevolucion(this);
        evidencia.setUrl(url);
        evidencia.setTipo(tipo);
        evidencia.setFecha(fecha);
        evidencias.add(evidencia);
    }

    public String codigo() {
        return CodigoPublico.devolucion(id, fecha.getYear());
    }
}

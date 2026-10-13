package ventas.pedido;

import java.math.BigDecimal;
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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pedido", schema = "ventas")
@Getter
@Setter
@NoArgsConstructor
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String codigo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CanalVenta canal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoPedido estado;

    @Column(nullable = false)
    private LocalDate fecha;

    /** UUID de Seguridad: claim sub del JWT del cliente (AGENTS.md punto 10). */
    @Column(name = "id_cliente")
    private UUID idCliente;

    /** UUID de Seguridad del vendedor (Retail). */
    @Column(name = "id_vendedor")
    private UUID idVendedor;

    @Column(name = "nombre_contacto")
    private String nombreContacto;

    @Column(name = "tipo_documento")
    private String tipoDocumento;

    @Column(name = "numero_documento")
    private String numeroDocumento;

    private String telefono;

    private String email;

    @Column(name = "modalidad_envio")
    private String modalidadEnvio;

    @Column(name = "costo_envio")
    private BigDecimal costoEnvio;

    private String destinatario;

    private String distrito;

    private String direccion;

    /** Destino que pide Despacho (G2, contrato 7.1). null en pedidos anteriores y en Retail. */
    private String departamento;

    private String provincia;

    @Column(name = "referencia_envio")
    private String referenciaEnvio;

    /** Acuerdo H-02: cabecera Idempotency-Key de 1.1. Única; null en pedidos anteriores al acuerdo. */
    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    /** reservaId que asigna Productos (G6) al confirmar la reserva. Null mientras está pendiente y en RETAIL. */
    @Column(name = "reserva_id", length = 100)
    private String reservaId;

    /** Solo RETAIL: código de la tienda donde se hizo la venta (acuerdo G4, R5). */
    @Column(name = "tienda_id", length = 40)
    private String tiendaId;

    @Column(name = "codigo_cupon")
    private String codigoCupon;

    private BigDecimal descuento;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String moneda;

    @Column(nullable = false)
    private BigDecimal subtotal;

    @Column(nullable = false)
    private BigDecimal total;

    /** Lo pone CrearPedidoService al registrar; se usa como fechaCreacion en las respuestas. */
    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DetallePedido> detalles = new ArrayList<>();

    public void agregarDetalle(DetallePedido detalle) {
        detalle.setPedido(this);
        detalles.add(detalle);
    }
}

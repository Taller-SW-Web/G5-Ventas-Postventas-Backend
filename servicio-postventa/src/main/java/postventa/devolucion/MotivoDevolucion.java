package postventa.devolucion;

/** Catálogo cerrado de ESP-07 (8.2). Los tres primeros exigen evidencia. */
public enum MotivoDevolucion {
    PRODUCTO_DEFECTUOSO(true),
    PRODUCTO_DANADO(true),
    PRODUCTO_INCORRECTO(true),
    OTRO(false);

    private final boolean requiereEvidencia;

    MotivoDevolucion(boolean requiereEvidencia) {
        this.requiereEvidencia = requiereEvidencia;
    }

    public boolean requiereEvidencia() { return requiereEvidencia; }
}

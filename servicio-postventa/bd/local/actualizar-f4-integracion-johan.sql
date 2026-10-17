-- Solo para PostgreSQL LOCAL. No ejecutar en Supabase.
-- Fuente: G5-Ventas-Postventas/infra/bd/ventas_postventa_schema.sql
-- Referencia de esquema: blob 9b05a8be3b1927b19a100da5b8da636adb1ebe77.
-- Conserva los datos y se puede ejecutar más de una vez.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'f4_integracion_johan' THEN
        RAISE EXCEPTION 'Este script solo admite la base local f4_integracion_johan';
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('postventa.devolucion') IS NULL THEN
        RAISE EXCEPTION 'Falta el esquema base: ejecutar crear-f4-integracion-johan.sql solo si la base está vacía';
    END IF;
END $$;

ALTER TABLE postventa.devolucion
    ADD COLUMN IF NOT EXISTS requiere_devolucion_fisica boolean NOT NULL DEFAULT true;
COMMENT ON COLUMN postventa.devolucion.requiere_devolucion_fisica IS
    'false solo en REEMBOLSO sin devolución física; en CAMBIO siempre true';

CREATE TABLE IF NOT EXISTS postventa.devolucion_recepcion (
    id                bigint generated always as identity primary key,
    id_devolucion     bigint       not null references postventa.devolucion(id) on delete cascade,
    operation_id      uuid         not null,
    sku               varchar(50)  not null,
    id_producto       varchar(64)  not null,  -- referencia externa: módulo F
    cantidad_recibida integer      not null,
    cantidad_apta     integer      not null,  -- solo esto se reintegra (ESP-08 regla 15)
    reintegro_estado  varchar(20)  not null,
    evidencia         varchar(500),
    id_actor          varchar(60),            -- sub de quien recibió (Gestor o almacén, rol ADMIN_VENTAS)
    fecha_hora        timestamptz  not null default now(),
    constraint ck_devolucion_recepcion_cantidad check (cantidad_apta >= 0 and cantidad_apta <= cantidad_recibida),
    constraint ck_devolucion_recepcion_estado check (reintegro_estado in ('PENDIENTE','COMPLETADO'))
);
CREATE INDEX IF NOT EXISTS ix_devolucion_recepcion_id_devolucion
    ON postventa.devolucion_recepcion(id_devolucion);
CREATE INDEX IF NOT EXISTS ix_devolucion_recepcion_operation_id
    ON postventa.devolucion_recepcion(operation_id);

CREATE TABLE IF NOT EXISTS postventa.historial_devolucion (
    id               bigint generated always as identity primary key,
    id_devolucion    bigint      not null references postventa.devolucion(id) on delete cascade,
    estado_anterior  varchar(20),            -- null en el registro inicial (SOLICITADA)
    estado_nuevo     varchar(20) not null,
    actor            varchar(20) not null,
    id_actor         varchar(60),            -- sub del cliente/Gestor, o módulo del canal
    detalle          text,
    fecha_hora       timestamptz not null default now(),
    constraint ck_historial_devolucion_actor check (actor in ('CLIENTE','CANAL','GESTOR','SISTEMA'))
);
CREATE INDEX IF NOT EXISTS ix_historial_devolucion_id_devolucion
    ON postventa.historial_devolucion(id_devolucion);
COMMIT;

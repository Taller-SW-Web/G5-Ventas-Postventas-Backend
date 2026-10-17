-- Preparación exclusiva de una base LOCAL vacía: f4_integracion_johan.
-- Fuente: G5-Ventas-Postventas/infra/bd/ventas_postventa_schema.sql
-- Referencia de esquema: blob 9b05a8be3b1927b19a100da5b8da636adb1ebe77.
-- Solo M2: no crea tablas de Ventas, roles ni políticas de Supabase.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'f4_integracion_johan' THEN
        RAISE EXCEPTION 'Este script solo admite la base local f4_integracion_johan';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'postventa') THEN
        RAISE EXCEPTION 'El esquema postventa ya existe. No se sobrescribe; usar actualizar-f4-integracion-johan.sql';
    END IF;
END $$;
CREATE SCHEMA postventa;
create table postventa.devolucion (
    id                     bigint generated always as identity primary key,
    id_pedido              bigint       not null,  -- referencia API: el pedido vive en M1
    tipo                   varchar(20)  not null,
    motivo                 varchar(255) not null,
    estado                 varchar(20)  not null,
    resolucion             varchar(30),
    id_autorizador         uuid,
    fundamento_rechazo     text,
    fecha                  date         not null default current_date,
    descripcion            text,
    id_cliente             uuid,        -- referencia externa: módulo G
    fecha_registro         timestamptz  not null default now(),   -- ESP-07 8.3, fechaCreacion
    fecha_resolucion       timestamptz,                           -- ESP-08 8.3, fechaResolucion
    referencia_operacion   varchar(100),                          -- ESP-08 8.3, referenciaOperacion
    requiere_devolucion_fisica boolean not null default true,     -- ESP-08 8.2b, regla 16
    constraint ck_devolucion_estado check (estado in
        ('SOLICITADA','EN_EVALUACION','APROBADA','RECHAZADA','COMPLETADA'))
);

comment on column postventa.devolucion.referencia_operacion is
    'reembolsoId de F4 o referencia de Despacho, según la resolución';

comment on column postventa.devolucion.id_pedido is 'Referencia API - el pedido vive en M1, sin FK física';

comment on column postventa.devolucion.requiere_devolucion_fisica is
    'false solo en REEMBOLSO sin devolución física (ESP-08 regla 16); en CAMBIO siempre true';

create table postventa.devolucion_item (
    id             bigint generated always as identity primary key,
    id_devolucion  bigint       not null references postventa.devolucion(id) on delete cascade,
    id_producto    varchar(64)  not null,   -- referencia externa: módulo F — texto, no bigint (H12)
    cantidad       integer      not null,
    constraint ck_devolucion_item_cantidad check (cantidad > 0)
);

create table postventa.evidencia_devolucion (
    id             bigint generated always as identity primary key,
    id_devolucion  bigint       not null references postventa.devolucion(id) on delete cascade,
    url            varchar(500) not null,
    tipo           varchar(30),
    fecha          date         not null default current_date
);

-- F3, 2.5b (ESP-08 8.2b, issue G6 #51 P-02 y P-05): una fila por línea de una confirmación de recepción física.
-- Las líneas de una misma confirmación comparten operation_id, que es el del reintegro en Productos.
create table postventa.devolucion_recepcion (
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

create index ix_devolucion_recepcion_id_devolucion on postventa.devolucion_recepcion(id_devolucion);
create index ix_devolucion_recepcion_operation_id  on postventa.devolucion_recepcion(operation_id);

-- RNF-03: un registro por cada cambio de estado del expediente de devolución. Nunca se edita ni se borra.
create table postventa.historial_devolucion (
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

create index ix_historial_devolucion_id_devolucion on postventa.historial_devolucion(id_devolucion);

create table postventa.reclamo (
    id                          bigint generated always as identity primary key,
    codigo                      varchar(30)  not null unique,
    id_pedido                   bigint,      -- referencia API opcional: el pedido vive en M1
    tipo                        varchar(20)  not null,
    motivo                      varchar(60)  not null,
    detalle                     text         not null,
    nombre_consumidor           varchar(150) not null,
    documento                   varchar(20)  not null,
    email                       varchar(150),
    telefono                    varchar(20),
    estado                      varchar(20)  not null,
    plazo_dias_habiles          integer      not null default 15,
    fecha_limite_respuesta      date         not null,
    respuesta_visible_cliente   text,
    canal                       varchar(20)  not null,
    id_cliente                  uuid,        -- referencia externa: módulo G
    fecha_registro              timestamptz  not null default now(),
    fecha_atencion              timestamptz,
    atendido_por                varchar(50),
    constraint ck_reclamo_plazo  check (plazo_dias_habiles > 0),
    constraint ck_reclamo_canal  check (canal in ('MARKETPLACE','CHATBOT','RETAIL')),
    constraint ck_reclamo_estado check (estado in ('REGISTRADO','EN_PROCESO','ATENDIDO','DERIVADO'))
);

comment on column postventa.reclamo.id_pedido is 'Referencia API opcional - el pedido vive en M1, sin FK física';

create table postventa.reembolso (
    id               bigint generated always as identity primary key,
    id_origen        bigint        not null,  -- referencia API: anulación (M1) o devolución (M2)
    tipo_origen      varchar(20)   not null,
    idempotency_key  varchar(100)  not null unique,
    monto            numeric(12,2) not null,
    moneda           char(3)       not null default 'PEN',
    id_transaccion   varchar(100),
    estado           varchar(20)   not null default 'PENDIENTE',
    id_autorizador   uuid,
    fecha            date          not null default current_date,
    id_pedido        bigint,       -- referencia API: el pedido vive en M1 (control de saldo)
    constraint ck_reembolso_tipo_origen check (tipo_origen in ('ANULACION','DEVOLUCION')),
    constraint ck_reembolso_estado      check (estado in ('PENDIENTE','EXITOSO','FALLIDO')),
    constraint ck_reembolso_monto       check (monto > 0),
    constraint uq_reembolso_origen      unique (tipo_origen, id_origen)
);

comment on column postventa.reembolso.id_origen is 'Referencia API - anulación (M1) o devolución (M2), sin FK física';

-- Historial de intentos de cada reembolso (ESP-10). Nunca se edita ni se borra un intento.
create table postventa.reembolso_intento (
    id              bigint generated always as identity primary key,
    id_reembolso    bigint       not null references postventa.reembolso(id) on delete cascade,
    numero_intento  integer      not null,
    estado          varchar(20)  not null,
    id_transaccion  varchar(100),
    resultado       text,
    fecha_hora      timestamptz  not null default now(),
    constraint ck_reembolso_intento_estado check (estado in ('PENDIENTE','EXITOSO','FALLIDO')),
    constraint uq_reembolso_intento unique (id_reembolso, numero_intento)
);

create table postventa.calificacion (
    id          bigint generated always as identity primary key,
    id_pedido   bigint       not null unique,  -- referencia API: el pedido vive en M1
    puntaje     integer      not null,
    comentario  text,
    canal       varchar(30)  not null,
    fecha       date         not null default current_date,
    constraint ck_calificacion_puntaje check (puntaje between 1 and 5),
    constraint ck_calificacion_canal   check (canal in ('MARKETPLACE','CHATBOT','RETAIL'))
);

comment on column postventa.calificacion.id_pedido is 'Referencia API - el pedido vive en M1, sin FK física';

create table postventa.agregado_ventas (
    periodo          varchar(7)    not null,  -- formato AAAA-MM
    canal            varchar(30)   not null,
    id_vendedor      uuid          not null,
    id_producto      varchar(64)   not null,  -- texto, no bigint (H12)
    unidades         integer       not null default 0,
    monto            numeric(14,2) not null default 0,
    actualizado_en   timestamptz   not null default now(),
    primary key (periodo, canal, id_vendedor, id_producto)
);

-- Métricas del dashboard por día y canal (ESP-15, ESP-16). Se actualiza con upsert.
create table postventa.agregado_metricas (
    fecha                   date          not null,
    canal                   varchar(20)   not null,
    pedidos_pagados         integer       not null default 0,
    pedidos_anulados        integer       not null default 0,
    pedidos_entregados      integer       not null default 0,
    devoluciones_aprobadas  integer       not null default 0,
    monto_reembolsado_pen   numeric(14,2) not null default 0,
    csat_suma               integer       not null default 0,
    csat_cantidad           integer       not null default 0,
    reclamos_registrados    integer       not null default 0,
    reclamos_atendidos      integer       not null default 0,
    actualizado_en          timestamptz   not null default now(),
    primary key (fecha, canal),
    constraint ck_agregado_metricas_canal check (canal in ('MARKETPLACE','CHATBOT','RETAIL'))
);

-- Eventos de M1 ya aplicados a los agregados (idempotencia del receptor 2.13)
create table postventa.evento_recibido (
    id_evento    uuid         primary key,  -- el mismo idEvento que genera M1
    tipo         varchar(40)  not null,
    recibido_en  timestamptz  not null default now()
);


COMMIT;

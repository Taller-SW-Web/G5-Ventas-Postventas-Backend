-- Pedidos de prueba para la demo de GET /api/v1/pedidos/me (acuerdo H-03 con Marketplace).
-- Cambiar el UUID por el sub del usuario de prueba de Marketplace (rol CLIENTE).
-- 11111111-1111-1111-1111-111111111111 es el "cliente activo" del kit de Seguridad (§6).
-- Se ejecuta en el SQL Editor de Supabase. Para borrarlos: ver el final del archivo.

do $$
declare
    cliente uuid := '11111111-1111-1111-1111-111111111111';
    p record;
    nuevo_id bigint;
begin
    for p in
        select * from (values
            ('MARKETPLACE', 'ENTREGADO',      189.80, now() - interval '12 days'),
            ('MARKETPLACE', 'EN_PREPARACION', 129.90, now() - interval '2 days'),
            ('CHATBOT',     'PAGADO',          59.90, now() - interval '3 hours')
        ) as t(canal, estado, total, creado)
    loop
        -- codigo es varchar(30) y NOT NULL: se guarda uno temporal y se reemplaza con el id
        insert into ventas.pedido (codigo, canal, estado, fecha, moneda, subtotal, descuento, total,
                                   costo_envio, creado_en, id_cliente, nombre_contacto, modalidad_envio)
        values ('TMP-' || left(replace(gen_random_uuid()::text, '-', ''), 24), p.canal, p.estado, p.creado::date,
                'PEN', p.total, 0, p.total, 0, p.creado, cliente, 'Cliente Demo', 'DELIVERY')
        returning id into nuevo_id;

        update ventas.pedido
           set codigo = 'PED-' || extract(year from creado_en)::int || '-' || lpad(id::text, 5, '0')
         where id = nuevo_id;
    end loop;
end $$;

-- Ver lo cargado:
-- select codigo, canal, estado, total, creado_en from ventas.pedido where nombre_contacto = 'Cliente Demo';

-- Borrar los datos de demo:
-- delete from ventas.pedido where nombre_contacto = 'Cliente Demo';

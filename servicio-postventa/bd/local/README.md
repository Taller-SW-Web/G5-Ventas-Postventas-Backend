# Base local para integración de Postventa

Estos scripts preparan exclusivamente `f4_integracion_johan` en PostgreSQL local.
No ejecutar en Supabase ni en la base compartida del equipo.

La aplicación mantiene `spring.jpa.hibernate.ddl-auto=validate`: Hibernate comprueba
el esquema; no lo crea ni lo actualiza automáticamente.

## Qué resuelven

Si el arranque informa que falta `postventa.devolucion.requiere_devolucion_fisica`,
la base conserva el esquema anterior a recepción física de F3. El esquema actual
incluye esa columna y las tablas `devolucion_recepcion` e `historial_devolucion`.

La fuente es el [SQL compartido](https://github.com/Taller-SW-Web/G5-Ventas-Postventas/blob/main/infra/bd/ventas_postventa_schema.sql),
blob de referencia `9b05a8be3b1927b19a100da5b8da636adb1ebe77`.
No son migraciones oficiales de Supabase: son utilidades de prueba local.

## 1. Elegir el script

- **Base existente con datos y esquema anterior:** `actualizar-f4-integracion-johan.sql`.
  Agrega la columna con el valor por defecto del SQL compartido (`true`) y crea las
  dos tablas con sus índices si faltan. No borra tablas ni registros.
  Puede repetirse. Si un objeto ya existe con una definición distinta, no intenta
  reconstruirlo: Hibernate debe señalar cualquier diferencia restante.
- **Base vacía, sin esquema `postventa`:** `crear-f4-integracion-johan.sql`.
  Crea las doce tablas de M2 a partir del esquema de referencia. Rechaza la ejecución
  si el esquema ya existe para no sobrescribir una base con información.

Los dos scripts se ejecutan en una transacción y comprueban el nombre de la base.
Antes de actualizar una base local con datos que necesites conservar, guarda un respaldo
desde pgAdmin (opción Backup de la base) o con `pg_dump`.

## 2. Aplicar en pgAdmin

1. Conectar al PostgreSQL **local** y seleccionar la base `f4_integracion_johan`.
2. Abrir Query Tool sobre esa base y ejecutar `SELECT current_database();`.
3. Abrir el script que corresponda según el paso anterior y ejecutarlo completo.
4. Si falla, revisar el primer error. Si la sesión quedó en transacción abortada,
   ejecutar `ROLLBACK;` antes de corregir y repetir. No cambiar Hibernate a `update`.

## Alternativa con el Docker de pruebas de Johan

Ejecutar desde la raíz del backend, con Docker Desktop y el contenedor activos:

```powershell
docker exec f5-eventos-test pg_isready -U f5_test -d f4_integracion_johan
```

Para una base existente:

```powershell
Get-Content -LiteralPath servicio-postventa/bd/local/actualizar-f4-integracion-johan.sql -Raw |
    docker exec -i f5-eventos-test psql -U f5_test -d f4_integracion_johan -v ON_ERROR_STOP=1
```

Si la base no existe, crearla y aplicar el esquema completo:

```powershell
docker exec f5-eventos-test createdb -U f5_test f4_integracion_johan
Get-Content -LiteralPath servicio-postventa/bd/local/crear-f4-integracion-johan.sql -Raw |
    docker exec -i f5-eventos-test psql -U f5_test -d f4_integracion_johan -v ON_ERROR_STOP=1
```

No volver a crear una base que ya existe. El contenedor de pruebas original usa `--rm`:
al detenerlo desaparecen sus bases; si eso ocurrió, corresponde preparar una base nueva.

## 3. Comprobar el esquema

```sql
SELECT current_database();
SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'postventa' AND table_name = 'devolucion'
  AND column_name = 'requiere_devolucion_fisica';
SELECT to_regclass('postventa.devolucion_recepcion') AS recepcion,
       to_regclass('postventa.historial_devolucion') AS historial;
```

La columna debe ser `boolean`, `NOT NULL` y tener por defecto `true`.
Las dos consultas `to_regclass` deben devolver sus nombres, no `null`.
La prueba completa es arrancar Postventa con `validate` y consultar su salud.

## 4. Arranque aislado en el puerto 18082

Compilar el módulo con Java 21 y Maven desde la raíz. Después ejecutar:

```powershell
& "$env:JAVA_HOME/bin/java.exe" `
  -jar servicio-postventa/target/servicio-postventa-0.0.1-SNAPSHOT.jar `
  "--server.address=127.0.0.1" `
  "--server.port=18082" `
  "--spring.config.import=" `
  "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/f4_integracion_johan" `
  "--spring.datasource.username=f5_test" `
  "--spring.datasource.password=f5_test_local" `
  "--spring.jpa.hibernate.ddl-auto=validate" `
  "--seguridad.habilitada=false" `
  "--integracion.simulado=true" `
  "--integracion.pasarela-simulada=true" `
  "--evidencias.almacen=local"
```

Estos datos corresponden al contenedor de pruebas de Johan (host 55432).
Si Luis usa otro PostgreSQL local, debe cambiar host, puerto, usuario y contraseña
por los de su instancia. En IntelliJ se pueden usar los mismos argumentos de programa.
Las integraciones se simulan para esta comprobación de arranque.

En otra terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:18082/actuator/health
```

Debe devolver `UP`. No asumir que el servicio arrancó solo porque Maven compiló.

## 5. Frontend contra ese puerto local

Solo después del arranque, en el `.env` local del frontend:

```properties
VITE_API_POSTVENTA_URL=http://127.0.0.1:18082
VITE_GATEWAY_URL=
```

El gateway vacío permite usar las URLs directas de microservicios con el cliente HTTP
actual. Si se usa un gateway real, conservar su URL y configurar sus rutas en vez de
vaciarla. No subir `.env` al repositorio. Reiniciar Vite tras cambiar variables.

## Validación de esta entrega

- Revisados los scripts contra el SQL compartido y las entidades actuales de M2.
- Maven de Postventa: `BUILD SUCCESS`, 289 pruebas reportadas, 0 fallos, 0 errores,
  13 omitidas por ser pruebas opcionales con PostgreSQL.
- Aplicación del SQL y arranque real pendientes: Docker Desktop falló al iniciar
  por un error de su gestor de inferencia (`dockerInference`). Esta entrega no afirma
  que los scripts ya se ejecutaron ni que el servicio arrancó contra una base actualizada.

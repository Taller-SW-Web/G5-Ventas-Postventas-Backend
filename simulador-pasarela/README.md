# Simulador de pasarela

Aplicación independiente para simular cobros y extornos. Puerto predeterminado: **8083**.
Los resultados se guardan en memoria y se pierden al reiniciar. El simulador no utiliza Supabase.

## Requisitos y ejecución

- Java 21.
- Maven disponible en la terminal.
- Ejecutar los comandos desde la raíz del repositorio.

Si `mvn` no está en PATH, sustituirlo por `& "RUTA_COMPLETA\mvn.cmd"` en PowerShell.

```powershell
mvn -pl simulador-pasarela -am package
java -jar simulador-pasarela/target/simulador-pasarela-0.0.1-SNAPSHOT.jar
```

Comprobar que arrancó desde otra terminal:

```powershell
Invoke-RestMethod http://localhost:8083/actuator/health
```

Detener la aplicación con Ctrl+C en la terminal donde se ejecuta.

## Configuración

| Propiedad | Predeterminado | Uso |
|---|---|---|
| `server.port` | `8083` | Puerto HTTP del simulador. |
| `pasarela.ventas-url` | `http://localhost:8081` | Dirección de M1; también acepta `VENTAS_URL`. |
| `pasarela.token-m1` | Vacío | Token para avisar a M1; también acepta `PASARELA_TOKEN_M1`. |
| `pasarela.demora-ms` | `2000` | Demora antes de resolver el cobro. |
| `pasarela.reintentos` | `3` | Reintentos adicionales después del envío inicial. |
| `pasarela.reintento-ms` | `1000` | Espera entre los intentos de notificación. |
| `pasarela.extorno.timeout-ms` | `10000` | Demora de la primera respuesta del escenario TIMEOUT. |

Las propiedades pueden sobrescribirse al iniciar, por ejemplo:

```powershell
java -jar simulador-pasarela/target/simulador-pasarela-0.0.1-SNAPSHOT.jar --server.port=8083 --pasarela.demora-ms=2000
```

Configurar el token real localmente, sin incluirlo en commits. Con seguridad habilitada, M1 exige el scope `ventas:pagos:webhook`.

## Cobros

`POST /pasarela/v1/cobros` devuelve HTTP 201 con `transaccionId` y `estado: PROCESANDO`.

```json
{
  "pedidoId": "PED-2026-00981",
  "monto": 120.50,
  "moneda": "PEN",
  "metodoPago": "TARJETA",
  "escenario": "APROBADO"
}
```

Escenarios: `APROBADO` y `RECHAZADO`. Si se omite, se utiliza APROBADO.
El monto debe ser positivo; las monedas admitidas son PEN y USD.

Ejemplo en PowerShell; para la integración real, sustituir pedido e importe por los de un pedido de prueba acordado con M1:

```powershell
$cuerpoCobro = @{
    pedidoId = 'PED-2026-00981'
    monto = 120.50
    moneda = 'PEN'
    metodoPago = 'TARJETA'
    escenario = 'APROBADO'
} | ConvertTo-Json

$cobro = Invoke-RestMethod -Method Post -Uri http://localhost:8083/pasarela/v1/cobros -ContentType 'application/json' -Body $cuerpoCobro
Invoke-RestMethod -Uri "http://localhost:8083/pasarela/v1/transacciones/$($cobro.transaccionId)"
```

Repetir la consulta después de la demora de procesamiento. Una transacción inexistente devuelve HTTP 404, `TRANSACCION_NO_ENCONTRADA`.

El resultado APROBADO/RECHAZADO es independiente de `notificadoM1`. Si M1 está caído o rechaza el aviso, el cobro conserva su resultado y `notificadoM1` permanece false.

El aviso se envía a `POST /api/v1/pedidos/{pedidoId}/pagos/notificacion` con los campos `transaccionId`, `resultado`, `monto`, `moneda`, `metodo` y `fechaPago` en formato ISO 8601.
Solo HTTP 200 confirma la notificación. Cada reintento conserva el identificador y la fecha del pago. Por defecto hay hasta cuatro envíos: uno inicial y tres reintentos.

Cada POST de cobro crea una transacción nueva; este endpoint no implementa la cabecera de idempotencia de extornos.

## Extornos

`POST /pasarela/v1/extornos` exige `X-Idempotency-Key` y devuelve HTTP 201 con `idTransaccion`, `estado` y `detalle`.

```powershell
$claveExtorno = [guid]::NewGuid().ToString()
$cabecerasExtorno = @{ 'X-Idempotency-Key' = $claveExtorno }
$cuerpoExtorno = @{
    referencia = 'PED-2026-00981'
    monto = 120.50
    moneda = 'PEN'
    escenario = 'EXITOSO'
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri http://localhost:8083/pasarela/v1/extornos -Headers $cabecerasExtorno -ContentType 'application/json' -Body $cuerpoExtorno
```

| Escenario | Comportamiento |
|---|---|
| `EXITOSO` u omitido | Guarda y devuelve un resultado EXITOSO. |
| `FALLIDO` | Guarda y devuelve un resultado FALLIDO, también con HTTP 201. |
| `TIMEOUT` | Guarda un resultado EXITOSO y demora la primera respuesta 10 segundos por defecto. |

- Repetir la misma clave con los mismos datos devuelve el mismo resultado e identificador.
- Reutilizar la clave con otros datos, incluido otro escenario, devuelve HTTP 409 `CLAVE_IDEMPOTENCIA_EN_CONFLICTO`.
- Los importes 10.0 y 10.00 se consideran iguales.
- Omitir la cabecera devuelve HTTP 400 `CLAVE_IDEMPOTENCIA_REQUERIDA`.
- Un reintento de TIMEOUT con la misma clave y datos recupera el resultado guardado sin repetir la demora.

Para probar TIMEOUT, generar una clave nueva, usar escenario TIMEOUT y un timeout del cliente menor que la demora del simulador:

```powershell
$claveTimeout = [guid]::NewGuid().ToString()
$cabecerasTimeout = @{ 'X-Idempotency-Key' = $claveTimeout }
$cuerpoTimeout = @{
    referencia = 'PED-2026-00981'
    monto = 120.50
    moneda = 'PEN'
    escenario = 'TIMEOUT'
} | ConvertTo-Json

try {
    Invoke-RestMethod -Method Post -Uri http://localhost:8083/pasarela/v1/extornos -Headers $cabecerasTimeout -ContentType 'application/json' -Body $cuerpoTimeout -TimeoutSec 2
} catch {
    Write-Host 'La solicitud no obtuvo respuesta a tiempo; consultar el resultado repitiendo la misma clave y datos.'
}

Invoke-RestMethod -Method Post -Uri http://localhost:8083/pasarela/v1/extornos -Headers $cabecerasTimeout -ContentType 'application/json' -Body $cuerpoTimeout
```

Un timeout no demuestra por sí solo que el extorno falló. Conservar la clave cuando el resultado sea incierto. Un intento nuevo después de un rechazo definitivo debe utilizar una clave nueva.

## Pruebas automáticas

```powershell
mvn -pl simulador-pasarela -am test
```

Las pruebas actuales del simulador no requieren M1, token real ni base de datos. Las pruebas de integración arrancan el simulador en un puerto aleatorio y sustituyen únicamente el cliente de notificaciones.

Pruebas adicionales del registro de eventos y de CSAT de M2, contra el PostgreSQL local f5-eventos-test:

```powershell
$env:F5_POSTGRES_TEST = 'true'
mvn -pl servicio-postventa -am "-Dtest=EventoRecibidoPostgresTest,CalificacionPostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
Remove-Item Env:F5_POSTGRES_TEST
```

Estas ocho pruebas (cuatro de eventos y cuatro de CSAT) exigen el contenedor local en 127.0.0.1:55432. Sin F5_POSTGRES_TEST=true se omiten deliberadamente para que la suite habitual no dependa de Docker.

## Integraciones pendientes

- Michael: comprobar el cobro y el callback contra M1 real con su token de servicio.
- Luis Alejandro: comprobar el contrato de extornos, la clave y el timeout con su cliente de F4.
- Fabrizio: conectar el receptor de eventos de M2 con sus proyecciones dentro de la misma transacción. El receptor completo todavía no está habilitado en Johan.

Las pruebas locales aprobadas no sustituyen estas verificaciones entre servicios.
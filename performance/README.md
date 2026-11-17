# Pruebas de performance (k6) · Hito 5

**Qué miden:** cuánto tarda la API en responder y cuánta carga aguanta. k6 simula muchos usuarios llamando a la vez.

**Criterio de aceptación:** el 95 % de las respuestas en menos de 800 ms (1 500 ms en el pico) y más del 99 % de checks correctos.

| Script | Qué prueba | Necesita token |
|---|---|---|
| `01-carga-gateway.js` | **Carga normal** (20 usuarios, 1 minuto) y **pico** (sube a 100 usuarios) sobre el API Gateway | No |
| `02-rate-limit.js` | Ráfaga de 150 llamadas sin token: el gateway debe cortar con **429** y `Retry-After` | No |
| `03-pedidos.js` · `consulta` | **5000 consultas** al listado de pedidos con 50 usuarios a la vez (directo a M1) | `TOKEN_GESTOR` (rol ADMIN_VENTAS) |
| `03-pedidos.js` · `creacion` | Crea N pedidos de prueba (por defecto 100) | `TOKEN_CANAL` (scope `ventas:pedidos:crear`) y `CLIENTE_ID` |

## Cómo correrlas (GitHub Actions, sin instalar nada)

1. **Actions → Performance (k6) → Run workflow**.
2. Elige la prueba y pulsa **Run**.
3. Al terminar, el **resumen del job** muestra la tabla (solicitudes, promedio, p95, máximo y checks). El JSON completo queda como **artefacto**.

Para las pruebas de pedidos, carga antes los tokens en **Settings → Secrets and variables → Actions**: `K6_TOKEN_GESTOR`, `K6_TOKEN_CANAL` y `K6_CLIENTE_ID`. Nunca se escriben en el código.

## Cómo correrlas en tu PC (opcional)

Con k6 instalado (https://grafana.com/docs/k6/latest/set-up/install-k6/):

```bash
k6 run performance/01-carga-gateway.js
k6 run performance/02-rate-limit.js
k6 run -e ESCENARIO=consulta -e TOKEN_GESTOR=<token> performance/03-pedidos.js
```

## Cosas a tener en cuenta al leer los resultados

- **Render gratis se duerme.** Cada script lo despierta antes de medir, pero el plan gratis tiene poca CPU: los tiempos son más altos que en un servidor pagado.
- **Las pruebas de pedidos van directo a M1** y no por el gateway, porque el gateway limita a 120–300 llamadas por minuto por token. Así medimos al servicio y no al límite. El límite se prueba aparte (`02-rate-limit.js`).
- **`creacion` escribe en la base real.** Los pedidos quedan con el nombre `PRUEBA K6` para poder identificarlos y limpiarlos.

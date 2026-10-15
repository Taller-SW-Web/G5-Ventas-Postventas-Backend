package postventa.dashboard;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import postventa.compartido.CanalVenta;
import postventa.dashboard.dto.MetricasResponse;

/** F6: endpoint 2.12 de api-postventas.md. Rol ADMIN_VENTAS (SecurityConfig). */
@RestController
@RequestMapping("/api/v2/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** desde y hasta obligatorios (AAAA-MM-DD); canal opcional del catálogo. */
    @GetMapping("/metricas")
    public ResponseEntity<MetricasResponse> metricas(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) CanalVenta canal) {
        return ResponseEntity.ok(dashboardService.metricas(desde, hasta, canal));
    }
}

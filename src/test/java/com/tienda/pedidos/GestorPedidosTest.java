package com.tienda.pedidos;

import com.tienda.pedidos.dto.ItemPedido;
import com.tienda.pedidos.dto.PedidoRequest;
import com.tienda.pedidos.dto.ResultadoPedido;
import com.tienda.pedidos.service.GestorPedidos;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pedidos de prueba de la Parte 1. Se escribieron contra el GestorPedidos ORIGINAL
 * y se mantienen sin cambios despues de la refactorizacion: si siguen pasando, el
 * comportamiento observable (rechazo/confirmacion, descuento, total) es el mismo.
 * Cada prueba hace rollback, asi que todas parten de los datos de data.sql.
 */
@SpringBootTest
@Transactional
class GestorPedidosTest {

    @Autowired GestorPedidos gestor;
    @Autowired JdbcTemplate jdbc;

    private ResultadoPedido procesar(String caso, long clienteId, ItemPedido... items) {
        ResultadoPedido r = gestor.procesarPedido(
            new PedidoRequest(clienteId, "cliente" + clienteId + "@tienda.com", List.of(items)));
        System.out.printf("[CASO] %-38s -> %s%n", caso, r);
        return r;
    }

    private static ItemPedido item(long productoId, int cantidad) {
        return new ItemPedido(productoId, cantidad);
    }

    // ---------- Rutas de validacion ----------

    @Test
    void stockInsuficienteRechazaElPedido() {
        ResultadoPedido r = procesar("1. Stock insuficiente", 5, item(4, 10)); // stock de Monitor = 5
        assertThat(r.isConfirmado()).isFalse();
        assertThat(r.getMotivoRechazo()).isEqualTo("Stock insuficiente: producto 4");
    }

    @Test
    void clienteInexistenteRechazaElPedido() {
        ResultadoPedido r = procesar("2. Cliente inexistente", 99, item(2, 1));
        assertThat(r.isConfirmado()).isFalse();
        assertThat(r.getMotivoRechazo()).isEqualTo("Cliente no registrado");
    }

    @Test
    void clienteMorosoDentroDelHorarioDeCorteEsRechazado() {
        LocalTime horaFija = LocalTime.of(10, 0); // se crea antes de activar el mock estatico
        try (MockedStatic<LocalTime> reloj = Mockito.mockStatic(LocalTime.class, Mockito.CALLS_REAL_METHODS)) {
            reloj.when(LocalTime::now).thenReturn(horaFija);
            ResultadoPedido r = procesar("3. Moroso a las 10:00 (dentro de corte)", 4, item(2, 2));
            assertThat(r.isConfirmado()).isFalse();
            assertThat(r.getMotivoRechazo()).isEqualTo("Cliente con deuda pendiente: $300000.0");
        }
    }

    @Test
    void clienteMorosoFueraDelHorarioDeCorteSePermite() {
        LocalTime horaFija = LocalTime.of(21, 0); // se crea antes de activar el mock estatico
        try (MockedStatic<LocalTime> reloj = Mockito.mockStatic(LocalTime.class, Mockito.CALLS_REAL_METHODS)) {
            reloj.when(LocalTime::now).thenReturn(horaFija);
            ResultadoPedido r = procesar("4. Moroso a las 21:00 (fuera de corte)", 4, item(2, 2));
            // subtotal 100.000, sin descuento, IVA 19 % -> 119.000
            assertThat(r.isConfirmado()).isTrue();
            assertThat(r.getTotal()).isCloseTo(119_000.0, within(0.01));
        }
    }

    // ---------- Descuentos por tipo de cliente ----------

    @Test
    void vipConSubtotalMayorAUnMillonTiene15PorCiento() {
        ResultadoPedido r = procesar("5. VIP subtotal 2.500.000 (15 %)", 1, item(1, 1));
        // 2.500.000 - 15 % = 2.125.000; + IVA 403.750 = 2.528.750
        assertThat(r.isConfirmado()).isTrue();
        assertThat(r.getTotal()).isCloseTo(2_528_750.0, within(0.01));
    }

    @Test
    void vipConSubtotalEntre500MilYUnMillonTiene10PorCiento() {
        ResultadoPedido r = procesar("6. VIP subtotal 800.000 (10 %)", 1, item(4, 1));
        // 800.000 - 10 % = 720.000; + IVA 136.800 = 856.800
        assertThat(r.getTotal()).isCloseTo(856_800.0, within(0.01));
    }

    @Test
    void frecuenteConMasDeDiezPedidosTiene8PorCiento() {
        ResultadoPedido r = procesar("7. FRECUENTE 12 pedidos previos (8 %)", 2, item(3, 2));
        // 240.000 - 8 % = 220.800; + IVA 41.952 = 262.752
        assertThat(r.getTotal()).isCloseTo(262_752.0, within(0.01));
    }

    @Test
    void frecuenteConMasDeTresPedidosTiene4PorCiento() {
        ResultadoPedido r = procesar("8. FRECUENTE 5 pedidos previos (4 %)", 3, item(3, 1));
        // 120.000 - 4 % = 115.200; + IVA 21.888 = 137.088
        assertThat(r.getTotal()).isCloseTo(137_088.0, within(0.01));
    }

    @Test
    void clienteEstandarNoTieneDescuento() {
        ResultadoPedido r = procesar("9. ESTANDAR sin descuento", 5, item(2, 1));
        assertThat(r.getTotal()).isCloseTo(59_500.0, within(0.01));
    }

    // ---------- Efectos de persistencia ----------

    @Test
    void pedidoConfirmadoSePersisteYDescuentaInventario() {
        ResultadoPedido r = procesar("10. Persistencia e inventario", 5, item(2, 3), item(3, 1));
        assertThat(r.getPedidoId()).isNotNull();
        Integer detalles = jdbc.queryForObject(
            "SELECT COUNT(*) FROM detalle_pedido WHERE pedido_id = ?", Integer.class, r.getPedidoId());
        Integer stockMouse = jdbc.queryForObject(
            "SELECT stock FROM inventario WHERE producto_id = 2", Integer.class);
        assertThat(detalles).isEqualTo(2);
        assertThat(stockMouse).isEqualTo(97);
    }
}

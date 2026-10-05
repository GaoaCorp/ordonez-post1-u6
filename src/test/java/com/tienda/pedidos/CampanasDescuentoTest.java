package com.tienda.pedidos;

import com.tienda.pedidos.dto.ItemPedido;
import com.tienda.pedidos.dto.PedidoRequest;
import com.tienda.pedidos.dto.ResultadoPedido;
import com.tienda.pedidos.service.GestorPedidos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Casos de las campanas CORPORATIVO y VOLUMEN (Black Friday inactiva, valor por defecto).
 * Se escribieron contra la version con los tres eslabones de cadena (Golden Hammer) y se
 * mantienen sin cambios despues de la correccion con Strategy: los totales deben coincidir.
 */
@SpringBootTest
@Transactional
class CampanasDescuentoTest {

    @Autowired GestorPedidos gestor;

    private ResultadoPedido procesar(String caso, long clienteId, ItemPedido... items) {
        ResultadoPedido r = gestor.procesarPedido(
            new PedidoRequest(clienteId, "cliente" + clienteId + "@tienda.com", List.of(items)));
        System.out.printf("[CASO] %-38s -> %s%n", caso, r);
        return r;
    }

    @Test
    void clienteConNitRecibeDescuentoCorporativo() {
        ResultadoPedido r = procesar("11. CORPORATIVO con NIT (10 %)", 6, new ItemPedido(2L, 2));
        // 100.000 - 10 % = 90.000; + IVA 17.100 = 107.100
        assertThat(r.getTotal()).isCloseTo(107_100.0, within(0.01));
    }

    @Test
    void pedidoDeMasDe20UnidadesRecibeDescuentoPorVolumen() {
        ResultadoPedido r = procesar("12. VOLUMEN 25 unidades (12 %)", 5, new ItemPedido(5L, 25));
        // 250.000 - 12 % = 220.000; + IVA 41.800 = 261.800
        assertThat(r.getTotal()).isCloseTo(261_800.0, within(0.01));
    }

    @Test
    void vipConVolumenTomaElMayorDescuento() {
        ResultadoPedido r = procesar("13. VIP 5 % vs VOLUMEN 12 % (gana 12)", 1, new ItemPedido(5L, 25));
        assertThat(r.getTotal()).isCloseTo(261_800.0, within(0.01));
    }

    @Test
    void vipSinCampanaConservaSuDescuento() {
        ResultadoPedido r = procesar("14. VIP 15 % sin campanas aplicables", 1, new ItemPedido(1L, 1));
        assertThat(r.getTotal()).isCloseTo(2_528_750.0, within(0.01));
    }

    @Test
    void validacionSigueCortandoAntesDeLasCampanas() {
        ResultadoPedido r = procesar("15. Stock insuficiente con 25 unidades", 6, new ItemPedido(4L, 25));
        assertThat(r.isConfirmado()).isFalse();
        assertThat(r.getMotivoRechazo()).isEqualTo("Stock insuficiente: producto 4");
    }
}

package com.tienda.pedidos;

import com.tienda.pedidos.dto.ItemPedido;
import com.tienda.pedidos.dto.PedidoRequest;
import com.tienda.pedidos.dto.ResultadoPedido;
import com.tienda.pedidos.service.GestorPedidos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Casos con la campana Black Friday ACTIVA (contexto y base de datos separados). */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
    "promo.black-friday.activa=true",
    "spring.datasource.url=jdbc:h2:mem:pedidosbf;MODE=LEGACY"
})
class BlackFridayTest {

    @Autowired GestorPedidos gestor;

    private ResultadoPedido procesar(String caso, long clienteId, ItemPedido... items) {
        ResultadoPedido r = gestor.procesarPedido(
            new PedidoRequest(clienteId, "cliente" + clienteId + "@tienda.com", List.of(items)));
        System.out.printf("[CASO] %-38s -> %s%n", caso, r);
        return r;
    }

    @Test
    void blackFridayAplica25PorCientoAClienteEstandar() {
        ResultadoPedido r = procesar("16. BLACK_FRIDAY cliente ESTANDAR (25 %)", 5, new ItemPedido(2L, 1));
        // 50.000 - 25 % = 37.500; + IVA 7.125 = 44.625
        assertThat(r.getTotal()).isCloseTo(44_625.0, within(0.01));
    }

    @Test
    void blackFridaySuperaAlDescuentoVip() {
        ResultadoPedido r = procesar("17. BLACK_FRIDAY 25 % vs VIP 15 %", 1, new ItemPedido(1L, 1));
        // 2.500.000 - 25 % = 1.875.000; + IVA 356.250 = 2.231.250
        assertThat(r.getTotal()).isCloseTo(2_231_250.0, within(0.01));
    }
}

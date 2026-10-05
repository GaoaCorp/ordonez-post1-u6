package com.tienda.pedidos.descuento;

import com.tienda.pedidos.validacion.ContextoPedido;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Campana Black Friday: 25 % fijo mientras la campana este activa en application.properties
@Component
public class DescuentoBlackFriday implements EstrategiaDescuento {
    private final boolean campanaActiva;

    public DescuentoBlackFriday(@Value("${promo.black-friday.activa}") boolean campanaActiva) {
        this.campanaActiva = campanaActiva;
    }

    @Override
    public double calcular(ContextoPedido contexto) {
        return campanaActiva ? 0.25 : 0.0;
    }
}

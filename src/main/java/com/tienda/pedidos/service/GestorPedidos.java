package com.tienda.pedidos.service;

import com.tienda.pedidos.descuento.CalculadorDescuentoFinal;
import com.tienda.pedidos.dto.ItemPedido;
import com.tienda.pedidos.dto.PedidoRequest;
import com.tienda.pedidos.dto.ResultadoPedido;
import com.tienda.pedidos.validacion.ContextoPedido;
import com.tienda.pedidos.validacion.ValidadorCliente;
import com.tienda.pedidos.validacion.ValidadorPedido;
import com.tienda.pedidos.validacion.ValidadorStock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Orquestador delgado: coordina validacion (solo Stock y Cliente), descuento, persistencia y notificacion
// sin conocer los detalles internos de ninguna de las cuatro capas.
@Service
public class GestorPedidos {
    private static final Logger log = LoggerFactory.getLogger(GestorPedidos.class);
    private static final double IVA = 0.19;

    private final ValidadorPedido primerValidador;
    private final CalculadorDescuentoFinal calculadorDescuento;
    private final PedidoRepository repository;
    private final NotificacionPedidoService notificacion;

    public GestorPedidos(ValidadorStock stock, ValidadorCliente cliente,
                         CalculadorDescuentoFinal calculadorDescuento, PedidoRepository repository,
                         NotificacionPedidoService notificacion) {
        this.primerValidador = stock;
        stock.encadenar(cliente);
        this.calculadorDescuento = calculadorDescuento;
        this.repository = repository;
        this.notificacion = notificacion;
    }

    public ResultadoPedido procesarPedido(PedidoRequest request) {
        log.info("Iniciando procesamiento de pedido para cliente {}", request.getClienteId());
        ContextoPedido contexto = new ContextoPedido(request);
        primerValidador.validar(contexto);
        if (contexto.isRechazado()) {
            log.warn("Pedido rechazado: {}", contexto.getMotivoRechazo());
            return ResultadoPedido.rechazado(contexto.getMotivoRechazo());
        }

        double subtotal = calcularSubtotal(request);
        contexto.setSubtotal(subtotal);

        double descuento = calculadorDescuento.calcular(contexto);
        double impuesto = (subtotal - subtotal * descuento) * IVA;
        double total = subtotal - (subtotal * descuento) + impuesto;

        Long pedidoId = repository.guardar(contexto, descuento, impuesto, total);
        notificacion.notificarConfirmacion(contexto, pedidoId, descuento, impuesto, total);
        log.info("Pedido {} confirmado. Total: {}", pedidoId, total);
        return ResultadoPedido.confirmado(pedidoId, total);
    }

    private double calcularSubtotal(PedidoRequest request) {
        double subtotal = 0;
        for (ItemPedido item : request.getItems()) {
            subtotal += repository.precioUnitario(item.getProductoId()) * item.getCantidad();
        }
        return subtotal;
    }
}

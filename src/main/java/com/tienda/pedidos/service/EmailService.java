package com.tienda.pedidos.service;

/**
 * Contrato de envio de correo. La implementacion de este proyecto imprime en consola
 * para no depender de un servidor SMTP real.
 */
public interface EmailService {
    void enviar(String destinatario, String asunto, String cuerpo);
}

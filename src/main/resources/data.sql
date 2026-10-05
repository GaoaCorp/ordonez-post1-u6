-- Clientes de prueba: uno por cada ruta de GestorPedidos
INSERT INTO clientes (id, nombre, tipo_cliente) VALUES (1, 'Ana VIP', 'VIP');
INSERT INTO clientes (id, nombre, tipo_cliente) VALUES (2, 'Bruno Frecuente', 'FRECUENTE');   -- 12 pedidos previos
INSERT INTO clientes (id, nombre, tipo_cliente) VALUES (3, 'Carla Frecuente', 'FRECUENTE');   -- 5 pedidos previos
INSERT INTO clientes (id, nombre, tipo_cliente) VALUES (4, 'Diego Moroso', 'MOROSO');         -- deuda pendiente
INSERT INTO clientes (id, nombre, tipo_cliente) VALUES (5, 'Elena Estandar', 'ESTANDAR');
INSERT INTO clientes (id, nombre, tipo_cliente, nit) VALUES (6, 'Comercial Andina SAS', 'ESTANDAR', '900123456-7'); -- campana CORPORATIVO

INSERT INTO productos (id, nombre, precio) VALUES (1, 'Portatil', 2500000);
INSERT INTO productos (id, nombre, precio) VALUES (2, 'Mouse', 50000);
INSERT INTO productos (id, nombre, precio) VALUES (3, 'Teclado', 120000);
INSERT INTO productos (id, nombre, precio) VALUES (4, 'Monitor', 800000);
INSERT INTO productos (id, nombre, precio) VALUES (5, 'Cable USB', 10000);

INSERT INTO inventario (producto_id, stock) VALUES (1, 10);
INSERT INTO inventario (producto_id, stock) VALUES (2, 100);
INSERT INTO inventario (producto_id, stock) VALUES (3, 50);
INSERT INTO inventario (producto_id, stock) VALUES (4, 5);
INSERT INTO inventario (producto_id, stock) VALUES (5, 200);

-- Deuda del cliente moroso
INSERT INTO facturas (cliente_id, monto, pagada) VALUES (4, 300000, false);
INSERT INTO facturas (cliente_id, monto, pagada) VALUES (4, 90000, true);

-- Historial de pedidos de los clientes frecuentes
INSERT INTO pedidos (cliente_id, total, estado) SELECT 2, 100000, 'CONFIRMADO' FROM SYSTEM_RANGE(1, 12);
INSERT INTO pedidos (cliente_id, total, estado) SELECT 3, 100000, 'CONFIRMADO' FROM SYSTEM_RANGE(1, 5);

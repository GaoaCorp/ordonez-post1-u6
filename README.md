# Post-contenido — Unidad 6: Antipatrones de Diseño

**Estudiante:** Gustavo Ordóñez · **Curso:** Patrones de Diseño de Software — Sexto semestre, UDES

## Descripción

Repositorio del post-contenido de la Unidad 6 de Patrones de Diseño de Software. Contiene un único proyecto Spring Boot (`pedidos-service`, en la raíz del repositorio) con dos partes:

1. **Parte 1:** diagnóstico y refactorización de los antipatrones combinados en `GestorPedidos`.
2. **Parte 2:** diagnóstico y corrección de un segundo antipatrón, que aparece al hacer crecer el mismo proyecto con tres campañas de descuento.

El historial de commits sigue el proceso completo: código original → pruebas que fijan su comportamiento → diagnóstico → refactorización → crecimiento con el antipatrón → diagnóstico → corrección.

## Cómo ejecutar

Requisitos: JDK 17 y Maven 3.8 o superior.

```bash
mvn test              # pedidos de prueba de ambas partes
mvn spring-boot:run   # levanta el servicio con H2 en memoria (consola en /h2-console)
```

Los datos de prueba están en `src/main/resources/data.sql`: un cliente por cada ruta (VIP, dos FRECUENTE, MOROSO, ESTANDAR), cinco productos con inventario y el historial de pedidos de los clientes frecuentes.

## Decisiones de diseño

### Parte 1 — GestorPedidos

#### Diagnóstico: God Object y Spaghetti Code combinados

Las líneas citadas corresponden a `src/main/java/com/tienda/pedidos/service/GestorPedidos.java` en el commit *"feat: implementar GestorPedidos con validacion, calculo, persistencia y notificacion en un solo metodo"*. El método `procesarPedido` ocupa las líneas 28–133 de un archivo de 134 líneas. La guía describe la versión completa de la clase con 340 líneas, porque incluye seis métodos auxiliares privados que no se reprodujeron aquí.

**1. Seis responsabilidades en un mismo método (God Object, viola SRP):**

| # | Responsabilidad | Líneas | Evidencia |
|---|---|---|---|
| 1 | Validación de stock | 31–44 | `SELECT stock FROM inventario` dentro del `for` (l. 37) |
| 2 | Validación de cliente y mora | 46–65 | `SELECT tipo_cliente` (l. 47), `SUM(monto)` de facturas (l. 53), `LocalTime.now()` (l. 57) |
| 3 | Cálculo de subtotal | 67–73 | `SELECT precio FROM productos` dentro del cálculo (l. 70) |
| 4 | Cálculo de descuento e impuesto | 75–96 | `if/else` por tipo de cliente y monto; IVA 0,19 escrito directamente en el código (l. 95) |
| 5 | Persistencia | 98–113 | `INSERT INTO pedidos`, `CALL IDENTITY()`, `INSERT INTO detalle_pedido`, `UPDATE inventario`, sin transacción |
| 6 | Notificación | 115–129 | Arma el correo con `StringBuilder` (l. 117–122) y lo envía (l. 125) |
| + | Registro (logging) | 29, 33, 41, 50, 59, 62, 127, 131 | Ocho llamadas a `log.*` repartidas por todas las secciones |

Es decir, la clase tiene **al menos seis razones distintas para cambiar**. Por ejemplo, si cambia el IVA, el formato del correo o el esquema de `inventario`, en los tres casos hay que editar el mismo método.

**2. Anidamiento profundo (Spaghetti Code):**

- **Mora (l. 52–64): tres niveles anidados.** `else if (MOROSO)` → `if (deuda > 0)` → `if (hora < 20:00) … else …`. La excepción por horario está enterrada en la rama más profunda.
- **Descuento (l. 77–93): dos niveles por tipo de cliente.** `if (VIP)` → `if/else if/else` por monto; `else if (FRECUENTE)` → `if/else if` por cantidad de pedidos. Una regla se elige por tipo de cliente, y dentro de cada tipo se decide de nuevo por otro criterio.

**3. Cuatro niveles de abstracción mezclados en la misma secuencia de líneas:**

- **SQL embebido:** 9 llamadas a `jdbcTemplate` (l. 37, 47, 53, 70, 86, 99, 104, 107, 110).
- **Reglas de negocio:** porcentajes de descuento, IVA, hora de corte a las 20:00.
- **Formato de texto:** `"Descuento aplicado: " + (int)(descuento * 100) + "%"`.
- **Infraestructura:** `try/catch` del envío y registros de log.

Para entender una regla de descuento hay que leer, en el medio, consultas SQL y concatenación de texto.

**4. Costo de agregar un tipo de cliente:** un tipo nuevo, por ejemplo CORPORATIVO, obliga a:

- Insertar otro `else if` en el bloque de las líneas 77–93.
- Si necesita datos propios, agregar otra consulta SQL dentro de ese bloque.
- Revisar si el texto del correo (l. 120–122) sigue siendo correcto.
- Probar de nuevo el método entero, porque no existe forma de probar solo la regla de descuento. Esa es la violación de OCP: el método se modifica en vez de extenderse.

**Conclusión del diagnóstico:** God Object, porque la clase concentra todo el flujo y depende directamente de `JdbcTemplate` (viola SRP y DIP). Spaghetti Code, porque el flujo de control es anidado y las abstracciones están mezcladas (viola OCP). Ambos aparecen combinados en un único método de 106 líneas.

#### Refactorización aplicada

Se aplicó un patrón por cada responsabilidad que lo justifica, y `procesarPedido` pasó de 106 a 21 líneas, con `GestorPedidos` como orquestador:

| Capa | Clases | Patrón |
|---|---|---|
| Validación | `ValidadorPedido`, `ValidadorStock`, `ValidadorCliente`, `ContextoPedido` | Chain of Responsibility |
| Descuento | `EstrategiaDescuento`, `DescuentoVip`, `DescuentoFrecuente`, `DescuentoEstandar`, `SelectorEstrategiaDescuento` | Strategy + selector por mapa |
| Persistencia | `PedidoRepository` | Repository (extracción de clase) |
| Notificación | `NotificacionPedidoService` | Extracción de clase |

**Decisión 1 — Validación como Chain of Responsibility.** Las validaciones tienen una dependencia real de orden y de corte anticipado. Si `ValidadorStock` rechaza el pedido, `ValidadorCliente` ni siquiera debe consultar la base de datos.

- **Alternativa descartada:** un método `validarTodo()` con una lista de `Predicate<ContextoPedido>`. Evalúa todos los predicados aunque el primero ya haya fallado, y no permite que un validador decida no delegar al siguiente.

**Decisión 2 — Descuento como Strategy y no como otro eslabón de la cadena.** Las reglas de descuento no dependen de un orden entre sí ni necesitan cortar el flujo: siempre se aplica exactamente una regla, determinada por el tipo de cliente.

- **Alternativa descartada:** modelarlo como cadena. Habría obligado a inventar un mecanismo para que solo un eslabón escriba el descuento. El `Map` del `SelectorEstrategiaDescuento` lo resuelve sin condicionales: agregar un tipo de cliente es crear una clase y registrarla en el mapa, sin tocar `GestorPedidos`.

**Comportamiento conservado:** los 10 pedidos de `GestorPedidosTest` se escribieron contra el `GestorPedidos` original y no se modificaron después de refactorizar. Siguen pasando, así que el comportamiento observable es el mismo: rechazo o confirmación, descuento y total. Ver la sección *Comparación antes/después*.

#### Ajustes respecto al código de referencia de la guía

Durante la implementación se encontraron cinco detalles del código de la guía que impedían que funcionara tal como está escrito. Se corrigieron así:

1. **Cliente inexistente.**
   - **Problema:** `queryForObject("SELECT tipo_cliente FROM clientes WHERE id = ?")` no devuelve `null` cuando no hay fila: lanza `EmptyResultDataAccessException`, y la rama `if (tipoCliente == null)` nunca se ejecutaría.
   - **Solución:** se usa una subconsulta escalar, `SELECT (SELECT tipo_cliente …)`, que devuelve una fila con `NULL`. Se aplicó igual en el original y en `ValidadorCliente`, para que la prueba de cliente inexistente compare lo mismo.
2. **`CALL IDENTITY()` en H2 2.x.**
   - **Problema:** H2 2.x solo conserva `IDENTITY()` en modo de compatibilidad.
   - **Solución:** se usa `jdbc:h2:mem:pedidosdb;MODE=LEGACY` con un pool de una sola conexión, porque `IDENTITY()` es por sesión.
3. **Orden de la cadena.**
   - **Problema:** en la guía, `this.primerValidador = stock.encadenar(cliente)` guarda `cliente` como primer eslabón, porque `encadenar` devuelve el siguiente. Así, `ValidadorStock` nunca se ejecutaría.
   - **Solución:** aquí `primerValidador = stock` y luego se encadena.
4. **Pedido sin items.**
   - **Problema:** el original rechazaba los pedidos sin items; el validador de la guía no lo hacía.
   - **Solución:** ese caso pasó a `ValidadorStock`, para conservar el comportamiento.
5. **Precios y transacción.**
   - **Problema:** la consulta de precios estaba dentro del cálculo, y las escrituras del pedido no tenían transacción.
   - **Solución:** la consulta se movió a `PedidoRepository.precioUnitario(...)`, para que `GestorPedidos` no tenga SQL. Además, `PedidoRepository.guardar(...)` quedó en `@Transactional`: las cuatro escrituras se aplican todas o ninguna, algo que el original no garantizaba.

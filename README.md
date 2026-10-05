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

### Parte 2 — Crecimiento del proyecto

#### Diagnóstico: Golden Hammer

Mercadeo pidió tres campañas: BLACK_FRIDAY (25 %), CORPORATIVO (10 % con NIT) y VOLUMEN (12 % con más de 20 unidades). Se implementaron como tres eslabones más de la cadena de validación: `PromocionBlackFriday`, `PromocionCorporativo` y `PromocionVolumen`, en `validacion/`. Las líneas citadas corresponden al commit *"feat: agregar 3 campanas de descuento como eslabones de la cadena de validacion"*.

**Evidencia:**

1. **No tienen dependencia de orden.** Los tres eslabones solo hacen `contexto.aplicarDescuentoCampana(x)`, y `aplicarDescuentoCampana` se queda con el máximo (`if (valor > this.descuentoCampana)`). El máximo es conmutativo: ejecutar `PromocionVolumen` antes que `PromocionCorporativo` da exactamente el mismo resultado. Todo lo contrario ocurre con `ValidadorStock` → `ValidadorCliente`, donde el orden sí importa, porque no tiene sentido consultar la mora de un pedido sin stock.
2. **Nunca usan el corte anticipado.** Ninguno llama a `contexto.rechazar(...)`; el comentario de `PromocionBlackFriday` lo admite: *"nunca rechaza -- este eslabon no valida nada"*. La propiedad que justificaba la cadena, decidir si el pedido continúa, no aparece en ninguno de los tres.
3. **Rompen el contrato de `ValidadorPedido`.** El contrato de esa clase es *"decidir si el pedido continúa o se rechaza"*, pero ahora la extienden clases que solo escriben en un campo compartido.
   - **Primer síntoma:** hubo que agregarle a `ContextoPedido`, que era el estado de la validación, un campo de cálculo (`descuentoCampana`).
   - **Segundo síntoma:** `GestorPedidos` tuvo que combinar dos fuentes de descuento a mano, con `Math.max(descuentoTipoCliente, contexto.getDescuentoCampana())`.
4. **La regla de combinación queda ambigua.** Si mercadeo pidiera sumar dos campañas en vez de tomar la mayor, la cadena no ofrece dónde expresarlo. Habría que cambiar `aplicarDescuentoCampana`, una clase de validación, o depender del orden de los eslabones, que es justamente lo que no debería importar.
5. **El motivo de la elección.** La cadena no se eligió porque el problema tuviera forma de cadena, sino porque *"los eslabones ya sabían cómo conectarse entre sí"* y ya había funcionado en la Parte 1.

**Conclusión:** esto es **Golden Hammer**: aplicar la herramienta que acaba de funcionar a un problema con otra forma, sin evaluar si corresponde. Las tres campañas tienen la misma forma que `DescuentoVip` y `DescuentoFrecuente`: calculan un porcentaje a partir de datos del pedido o del cliente, sin depender del orden. El patrón que corresponde, Strategy, ya existía en el proyecto.

#### Corrección: extender el Strategy existente

- `DescuentoBlackFriday`, `DescuentoCorporativo` y `DescuentoVolumen` implementan `EstrategiaDescuento`, igual que los descuentos por tipo de cliente.
- `CalculadorDescuentoFinal` combina la estrategia por tipo de cliente con el máximo de las campañas: `Math.max(porTipoCliente, max(campañas))`. Es la misma regla de negocio, pero ahora en un solo lugar y sin estado mutable compartido. Si la regla cambiara a sumar, el cambio viviría solo en esa clase.
- `GestorPedidos` delega el descuento completo a `CalculadorDescuentoFinal`. `ValidadorPedido` vuelve a tener únicamente los dos eslabones que justifican la cadena: `ValidadorStock` y `ValidadorCliente`.

**Decisión — Strategy en vez de más eslabones de cadena.** Las campañas calculan un porcentaje sin depender del orden de evaluación ni necesitar cortar el flujo, que es la propiedad que sí tienen `ValidadorStock` y `ValidadorCliente`.

- **Alternativa descartada:** mantenerlas en la cadena, porque es precisamente la causa del antipatrón diagnosticado.

**Decisión — eliminar, no comentar.** `PromocionBlackFriday`, `PromocionCorporativo`, `PromocionVolumen` y el campo `descuentoCampana` se eliminaron con `git rm`. No quedan ni comentados. Comentar código "por si acaso" es el mecanismo con el que nace un Lava Flow: nadie se atreve a borrarlo después. La referencia histórica queda en el commit de la Parte 2, no en el código activo.

**Verificación:**

- Las pruebas `CampanasDescuentoTest` y `BlackFridayTest` se escribieron contra la versión con Golden Hammer y no se modificaron al corregirla.
- Siguen pasando, así que los totales son idénticos en ambos diseños.

## Comparación antes/después

Las salidas se tomaron de ejecuciones reales de `mvn test` (JDK 17, Maven 3.9) sobre cuatro puntos del historial. Las pruebas imprimen una línea `[CASO]` por pedido. En las cuatro versiones el resultado fue **BUILD SUCCESS**: 10 pruebas en la Parte 1 y 17 en la Parte 2.

**Parte 1 — `GestorPedidos` original vs. refactorizado.** Los resultados son idénticos.

| Caso | Original (commit `test: …`) | Refactorizado (commit `refactor: reducir …`) |
|---|---|---|
| 1. Stock insuficiente | RECHAZADO "Stock insuficiente: producto 4" | RECHAZADO "Stock insuficiente: producto 4" |
| 2. Cliente inexistente | RECHAZADO "Cliente no registrado" | RECHAZADO "Cliente no registrado" |
| 3. Moroso a las 10:00 | RECHAZADO "Cliente con deuda pendiente: $300000.0" | RECHAZADO "Cliente con deuda pendiente: $300000.0" |
| 4. Moroso a las 21:00 | CONFIRMADO total 119.000,00 | CONFIRMADO total 119.000,00 |
| 5. VIP 2.500.000 (15 %) | CONFIRMADO total 2.528.750,00 | CONFIRMADO total 2.528.750,00 |
| 6. VIP 800.000 (10 %) | CONFIRMADO total 856.800,00 | CONFIRMADO total 856.800,00 |
| 7. FRECUENTE 12 pedidos (8 %) | CONFIRMADO total 262.752,00 | CONFIRMADO total 262.752,00 |
| 8. FRECUENTE 5 pedidos (4 %) | CONFIRMADO total 137.088,00 | CONFIRMADO total 137.088,00 |
| 9. ESTANDAR | CONFIRMADO total 59.500,00 | CONFIRMADO total 59.500,00 |
| 10. Persistencia e inventario | CONFIRMADO total 321.300,00 (2 detalles, stock 100 → 97) | CONFIRMADO total 321.300,00 (2 detalles, stock 100 → 97) |

**Parte 2 — campañas con Golden Hammer vs. campañas con Strategy.** Los resultados son idénticos.

| Caso | Eslabones de cadena (commit `feat: agregar 3 campanas …`) | Strategy (commit `refactor: mover las 3 campanas …`) |
|---|---|---|
| 11. CORPORATIVO con NIT (10 %) | CONFIRMADO total 107.100,00 | CONFIRMADO total 107.100,00 |
| 12. VOLUMEN 25 unidades (12 %) | CONFIRMADO total 261.800,00 | CONFIRMADO total 261.800,00 |
| 13. VIP 5 % vs. VOLUMEN 12 % | CONFIRMADO total 261.800,00 | CONFIRMADO total 261.800,00 |
| 14. VIP 15 % sin campañas | CONFIRMADO total 2.528.750,00 | CONFIRMADO total 2.528.750,00 |
| 15. Stock insuficiente con 25 unidades | RECHAZADO "Stock insuficiente: producto 4" | RECHAZADO "Stock insuficiente: producto 4" |
| 16. BLACK_FRIDAY cliente ESTANDAR (25 %) | CONFIRMADO total 44.625,00 | CONFIRMADO total 44.625,00 |
| 17. BLACK_FRIDAY 25 % vs. VIP 15 % | CONFIRMADO total 2.231.250,00 | CONFIRMADO total 2.231.250,00 |

En la Parte 2, los casos 1 a 10 de la Parte 1 también se repitieron sin cambios en ambas versiones. Los números de pedido (`pedidoId`) pueden variar entre ejecuciones porque dependen del orden en que JUnit corre las pruebas. Los totales y los motivos de rechazo no varían.

## Herramientas utilizadas

- Java 17, Spring Boot 3.2, Spring JDBC (`JdbcTemplate`), H2 Database
- JUnit 5, AssertJ, Mockito (`mockStatic` de `LocalTime` para fijar la hora de corte en las pruebas)
- Apache Maven, Git, GitHub

## Conclusiones

Diagnosticar antes de refactorizar cambió la calidad de la solución. Contar las responsabilidades por líneas y los niveles de anidamiento mostró que `GestorPedidos` necesitaba **dos** patrones distintos y no uno: Chain of Responsibility, porque las validaciones tienen orden y corte anticipado, y Strategy, porque los descuentos solo dependen del tipo de cliente. La Parte 2 dejó la lección más fuerte: el mismo patrón que resolvió bien un problema se convirtió en antipatrón al aplicarlo por costumbre a otro con distinta forma. La pregunta que lo detectó fue concreta: ¿estas clases dependen del orden o necesitan cortar el flujo? Escribir primero las pruebas contra el código original permitió refactorizar dos veces con evidencia de que el comportamiento no cambió. Y borrar en vez de comentar el código descartado evitó convertir la corrección en un Lava Flow.

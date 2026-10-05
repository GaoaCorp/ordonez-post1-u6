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

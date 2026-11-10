# Parches sobre iec61850bean 1.9.0

La app compila copias modificadas de **dos clases** de la librería:

    app/src/main/java/com/beanit/iec61850bean/ServerAssociation.java
    app/src/main/java/com/beanit/iec61850bean/SclParser.java     (desde 2026-10-21)

El `.jar` original se sigue usando para todo lo demás. En `app/build.gradle` se
excluyen de él las clases parcheadas (y sus clases internas) y se compilan las
nuestras en su lugar; sin esa exclusión el build falla por clase duplicada.

La librería es **Apache 2.0**, que permite modificarla y redistribuirla conservando
el aviso de copyright (y es compatible con la GPL v3 de este proyecto). El
encabezado original está intacto en cada archivo, y cada cambio está marcado.

## Por qué hubo que parchearla

Los tres primeros cambios salieron de probar el servidor contra un cliente MMS
real (la misma librería que usa IEDnavigatorPRO del lado cliente) con archivos
CID de fabricante, y son en `getWriteResult()`. El cuarto, del 2026-10-19, es en
`handleGetDataSetNamesRequest()`.

### 1. Sólo aceptaba ctlModel 1 y 2

El original resolvía por su cuenta el modelo de control y respondía
`object_access_unsupported` (error 9) para ctlModel 3 y 4, **sin llamar al
`ServerEventListener`**. Es decir que `ControlEngine` no llegaba a enterarse
del mando.

Eso deja afuera justo los modelos que importan:

- `ctlModel = 4` (sbo-with-enhanced-security) es el habitual en interruptores de
  relés reales, y el que IEDnavigatorPRO implementó a mano y verificó contra un
  relé real.
- `ctlModel = 3` (direct-with-enhanced-security) tampoco pasaba.

Ahora esos casos devuelven `null` (permitir) y decide `ControlEngine`, que es
donde están las reglas de SBO, correlación de `ctlNum`/`origin`, enclavamiento
y sincronismo.

**Lo que NO se tocó:** ctlModel 1 y 2 siguen exactamente como estaban. Para
ctlModel 2 la selección la sigue haciendo la librería (el cliente selecciona
*leyendo* el nodo `SBO`, una lectura que nunca pasa por nuestro motor), así que
`ControlEngine` no exige selección propia en ese caso. El reparto está
documentado en `ControlEngine.procesar()`.

### 2. Rechazaba toda escritura a SBOw, SBO y Cancel

Cualquier nodo `FC=CO` que no se llamara `Oper` recibía error 9 con el mensaje
*"the operation is not allowed yet"*. Con eso el **select-with-value** del
modelo reforzado era imposible: es una escritura sobre `SBOw`.

Ahora se deja pasar y decide `ControlEngine`.

### 3. Descartaba todo menos `ctlVal`

El original hacía:

```java
if (fcModelNodeCopy.fc == Fc.CO) {
    // TODO timeactivate operate
    fcModelNodeCopy = (FcModelNode) fcModelNodeCopy.getChild("ctlVal");
    // TODO write origin and ctlNum if they exist
}
```

O sea que al `ServerEventListener` **nunca** le llegaban `ctlNum`, `origin`,
`T`, `Test` ni `Check`. Consecuencias, las dos verificadas:

- La correlación `SBOw` → `Oper` de IEC 61850-7-2 §20 no se podía validar. La
  bitácora de IEDnavigatorPRO llama a esa correlación *"el punto más frágil del
  refactor"*: si el `ctlNum` no coincide, un IED real rechaza el segundo paso.
  Nuestro simulador lo aceptaba siempre.
- Las reglas de `ControlPolicy` que dependen del bit `Check` (enclavamiento por
  CILO y sincronismo por RSYN) **eran código muerto**: el `BdaCheck` no llegaba
  nunca.

Ahora la estructura de control llega entera, y además queda escrita en el
modelo, que es lo que hace un servidor real con los parámetros del último mando.

> **Cuidado al modificar `IEDServerService.write()`:** la librería sólo
> interpreta un rechazo si la lista de `ServiceError` tiene **exactamente un
> elemento por cada atributo escrito**:
>
> ```java
> if (serviceErrors == null || serviceErrors.size() != totalBdasToBeWritten.size())
>     // lo da todo por exitoso
> ```
>
> Como ahora el lote trae la estructura completa (7 atributos y no 1), devolver
> un solo error haría que el rechazo se ignorara **en silencio** y el mando se
> aplicara igual.

### 4. Anunciaba DataSets de otro LD con nombre de prefijo común

`ServerModel.getDataSetNames(ld)` filtra con `startsWith(ld)` **sin la barra**.
Si un LD se llama igual que el principio de otro (`...Ln1` y
`...Ln1_8521PermOverr`), el GetNameList del corto anuncia también los DataSets
del largo; la consulta siguiente responde `PARAMETER_VALUE_INAPPROPRIATE` y
`retrieveModel()` del cliente **aborta entero**. Afecta a 8 casos en 6 archivos
de fabricantes, entre ellos un SCD de subestación.

`ServerModel` es `final` y package-private en ese método, así que el arreglo va
en `handleGetDataSetNamesRequest()`: arma la lista desde `getDataSets()`
comparando contra `ld + "/"`.

Hasta el 2026-10-18 esto se esquivaba en la app, registrando en
`SclFileParser.blindarDataSetsPorPrefijo()` un **alias** del DataSet en el LD
corto. Así `retrieveModel()` completaba, pero el cliente veía en el LD corto un
DataSet que el SCL no le da: el modelo cargaba, y cargaba mal (un CID exportado
desde el cliente lo habría heredado). Con el parche el alias sobra, y además
seguiría apareciendo, porque es un DataSet registrado de verdad: se quitó.

### 5. Los arreglos de atributos tomaban todos el valor del primero (`SclParser`)

`SclParser.createArrayOfDataAttributes()` le pasaba a cada elemento de un arreglo
de DA el mismo nodo XML —el primer `DAI`/`SDI` con ese nombre— e ignoraba el
atributo `ix` (la librería lo tenía como `TODO`). Todos los elementos quedaban con
el valor del 0.

La copia es el fuente original de la v1.9.0 (verificado en IEDnavigatorPRO:
compilado con `--release 8` coincide con el `.class` del jar en sus 23 métodos)
con un cambio: cada elemento busca el `DAI`/`SDI` hermano con su `ix`. Sin `ix`
en ninguno, el comportamiento original; con `ix` pero no para ese elemento, el
valor por defecto.

**Va junto con un cambio del lado de la app:** `SclFileParser.preprocess()`
expandía los `DA` con `count` en atributos sueltos (`crvPts00`…`crvPts29`). El
modelo servido no tenía ningún arreglo —un IED real expone `crvPts(0)`…— y los
valores se perdían, porque el archivo los nombra `crvPts` con `ix`. Ahora los DA
quedan como arreglo; `SDO` y `BDA` se siguen expandiendo (la librería no arma
arreglos de ninguno de los dos). Y un `count` no numérico deducido marca el
documento como modificado: antes lo tapaba la expansión.

El caso real son los puntos de curva `crvPts` de algunos relés, que el archivo
declara como arreglo con `ix`.

### 6. Los ajustes `SG` no existían como `SE` (`SclParser`)

La librería crea cada atributo solo con el FC que declara su `DOType`. En
IEC 61850-7-2 el `SE` es el búfer de edición del mismo ajuste, y un DataSet puede
referenciarlo: un DataSet de ajustes de un relé de alimentador tiene sus 72 ajustes en `SE`.
Un `FCDA fc="SE"` sobre un `DA fc="SG"` no se resolvía y `SclParser` rechazaba el
archivo entero ("Specified FCDA ... not found in Model"). Pasaba con el CID que el
cliente de escritorio exporta de ese equipo, y pasaría con uno de fabricante.

En `createFcDataObjects()`, cada `DA` con `fc="SG"` (arreglos incluidos) se crea
también con `fc="SE"` y los mismos valores. Mismo cambio, línea por línea, que en
IEDnavigatorPRO (`92af96a`), donde se verificó contra un corpus de 168 archivos:
la firma de cada modelo sin `SE` no cambia, y 5 archivos ganan su copia `SE`.

### 7. No mandaba LastApplError ni CommandTermination (`ServerAssociation`, 5.4.0)

IEC 61850-8-1 define dos mensajes que el servidor manda por su cuenta durante un control, como
InformationReport con lista de variables:

- **LastApplError**, antes de la respuesta negativa a un SBOw/Oper/Cancel rechazado: CntrlObj,
  Error, Origin {orCat, orIdent}, ctlNum y **AddCause** (el motivo).
- **CommandTermination**, después de la respuesta a un Oper aceptado con ctlModel 3 o 4: la
  variable Oper sola si terminó bien; LastApplError + Oper si falló.

La biblioteca no mandaba ninguno: los rechazos llegaban al cliente sin motivo y un cliente que
espera la terminación de un mando reforzado no la recibía nunca. Ahora:

- `handleSetDataValuesRequest()` reconoce una escritura de un solo nodo SBOw/Oper/Cancel y guarda la
  copia con los valores del cliente y el primer error que devolvió el listener.
- Rechazo → manda el LastApplError en el acto (antes de la respuesta) y responde
  `object-access-denied` (3), como un IED real; el AddCause sale de `addCauseDe()`, que mapea el
  motivo de ControlPolicy / ControlEngine (local/estación 2, modo o Test 8, bloqueo 9,
  enclavamiento 10, sincronismo 11, no seleccionado 18, status-only 1, otro 0).
- Oper aceptado con ctlModel 3/4 → la terminación positiva queda pendiente y el bucle de pedidos
  la manda justo después de la respuesta.

Verificado con el cliente de IEDNavigator: terminación positiva a 4–11 ms del Oper; LOCAL rechaza
orCat 1–3 con AddCause 2; Modo Test, AddCause 8. El cliente de la biblioteca no los ve: descarta
esos mensajes.

**5.5.0, terminación diferida.** Después del `write()` del listener la asociación toma el plan del
mando con `Terminacion.tomar()` (el listener lo deja con `anunciar()` en el mismo hilo). Si el plan
trae retardo o cambios finales, la terminación ya no sale pegada a la respuesta: un reloj aparte
(`Terminacion.programar`) aplica los cambios, los publica y recién ahí manda la terminación,
positiva (Oper) o negativa (LastApplError con el AddCause del plan + Oper, `terminacionNegativa`).

### Ojo: el jar se arma con Gradle y Gradle no lo rearmaba

Las clases originales se sacan del jar en la tarea `iec61850SinServerAssociation`
de `app/build.gradle`. Gradle no toma un cambio en sus `exclude` como cambio de
entradas: al agregar `SclParser` el 21-10 la tarea siguió "UP-TO-DATE" y el jar
del 27-08 siguió trayendo el `SclParser` original. El APK llevaba **las dos
copias** y en el equipo ganaba la original (`classes.dex`): los parches 5 y 6 no
llegaron al equipo hasta el 22-10. Las pruebas en PC no lo veían porque arman su
propio classpath. Ahora la tarea se rearma siempre y **falla el build** si una clase
reemplazada queda en el jar (probado sacando el `exclude`). Para revisar un APK:
`dexdump` sobre cada `classes*.dex` y contar `Lcom/beanit/iec61850bean/SclParser;`.

## Lo que NO se parcheó, y se resolvió del lado de la app

- **`ServerSap.setValues()` exige copias.** Lee `bda.mirror`, que sólo lo deja
  puesto `copy()`; con un atributo tomado directo del modelo tira NPE. Y además
  sólo emite reporte si el valor de la copia **difiere** del que tiene el
  modelo. Por eso `ControlEngine` y `EstadoLocal` copian primero y modifican la
  copia. Antes de esto **no salía ningún reporte por RCB**.

## Si se sube de versión la librería

Hay que rehacer los parches sobre el nuevo fuente: bajar
`iec61850bean-<version>-sources.jar` de Maven Central, extraer
`ServerAssociation.java` y `SclParser.java`, y reaplicar los siete cambios (están
marcados con el comentario `PARCHE IEDSimulator`). Conviene revisar antes si la
versión nueva ya resolvió alguno.

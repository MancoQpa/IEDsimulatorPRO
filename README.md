# IEDsimulatorPRO

Simulador de IED IEC 61850 para **Android**. Carga un archivo SCL (ICD, CID o SCD) en el
teléfono o la tablet y lo sirve por **MMS** en la red Wi-Fi, como si fuera el equipo real.
Pensado para practicar con [IEDNavigator PRO](https://mancoqpa.github.io/IEDnavigatorPRO/)
—o con cualquier cliente IEC 61850— sin tener un relé a mano.

**Descarga:** el APK de la última versión está en
[Releases](https://github.com/MancoQpa/IEDsimulatorPRO/releases/latest).

Herramienta de uso educativo. No apta para pruebas FAT/SAT, comisionamiento ni maniobras en
instalaciones en servicio.

## Qué hace

- **Carga archivos SCL** (ICD, CID, SCD) desde el almacenamiento. En un SCD con varios IED,
  se elige cuál simular.
- **Servidor MMS** con el modelo completo del archivo: dispositivos lógicos, nodos lógicos,
  objetos y atributos, DataSets y bloques de reporte.
- **Mandos** con los cuatro modelos de control de IEC 61850-7-2, incluido el **SBO con
  seguridad reforzada** (`SBOw` → `Oper`, con su `ctlNum` y `origin`), y `Cancel`.
- **Respuestas como las de un equipo real:**
  - rechazos con su motivo (**LastApplError** con **AddCause**);
  - **CommandTermination** positiva o negativa en los mandos con seguridad reforzada;
  - autoridad de mando **local / bahía / estación / remoto**, y llave local por aparato;
  - enclavamiento (CILO), verificación de sincronismo (RSYN) y modo de prueba (Test).
- **Ventana de maniobra local:** posición inicial de cada aparato, como a pie de equipo, y
  cómo responde al mando: normal, **sin cablear** (queda en intermedio), **trabado** (no se
  mueve) o **lento** (recorrido de unos segundos); sincronismo en falla para los interruptores.
- **Fallas del interruptor** (5.6.0): falla de apertura o de cierre (acepta la orden, no se
  mueve y termina negativo con AddCause 16), **resorte descargado** (no cierra) y **gas SF6** en
  alarma o en bloqueo, con `CBOpCap` y las alarmas del `SIMG` cuando el modelo las tiene;
  **tiempos de apertura y de cierre** configurables, con paso por intermedio. Los pulsadores a
  pie de equipo respetan las mismas fallas.
- **Interruptores por polo:** un XCBR por fase y otro trifásico comandados por un solo CSWI
  se mueven juntos.
- **Modo test del IED** (5.7.0): botón NORMAL / MODO TEST en la ventana de maniobra. El `Beh`
  de cada nodo se deriva del modo del LD (`LLN0.Mod`) y de su `Mod` propio, como en un relé
  (IEC 61850-7-4, anexo A); un cliente que opera un `Mod` por MMS obtiene el mismo efecto. En
  test, los nodos solo aceptan mandos con el bit `Test`.
- **Modo simulación:** editar medidas y estados desde el árbol del modelo; los cambios salen
  por los reportes al cliente conectado.
- Corre como servicio en segundo plano, con notificación.

## Uso

1. Instalar el APK (Android 7.0 o superior). Android pedirá permitir la instalación desde
   esa fuente.
2. Copiar el archivo SCL al teléfono o tablet (hay uno de ejemplo en `sample_files/`).
3. En la app: **Seleccionar SCL**, revisar el modelo y tocar **Iniciar servidor**.
4. Anotar la IP y el puerto que muestra la app (por defecto, puerto **10102**).
5. En IEDNavigator PRO, modo **Cliente**: ingresar esa IP y ese puerto, y **Conectar**.

La PC y el teléfono tienen que estar en la misma red Wi-Fi.

> **Actualizar desde una versión anterior a la 5.5.1:** por un cambio de firma hay que
> desinstalar la versión anterior una vez. Los archivos SCL del teléfono no se tocan.

## Limitaciones

- Solo MMS sobre TCP/IP: no publica ni recibe GOOSE ni Sampled Values.
- No usa el puerto 102 del estándar: Android no permite puertos menores a 1024 sin root.
- Requiere Wi-Fi local (no funciona sobre datos móviles por NAT).
- Rendimiento limitado comparado con un IED real.

## Compilar

Requiere un JDK 17 o 21 y el SDK de Android.

```bash
./gradlew assembleDebug      # APK de depuración
./gradlew assembleRelease    # APK de publicación (firmado si está la llave del proyecto)
```

La app usa [iec61850bean](https://github.com/beanit/iec61850bean) 1.9.0 (Apache 2.0), con
dos clases modificadas; el detalle de cada cambio está en
[`PARCHES-LIBRERIA.md`](PARCHES-LIBRERIA.md).

## Licencia

GNU General Public License v3.0 (ver [`LICENSE`](LICENSE)), igual que IEDNavigator PRO.
Desarrollado por Emilio Medina.

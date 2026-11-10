# Reglas de R8/ProGuard para el build release.
#
# Hoy el release va con minifyEnabled=false, asi que estas reglas todavia no se
# aplican. Estan escritas de antemano porque el dia que se active la ofuscacion
# hay una trampa concreta que romperia la app SIN dar ningun error visible.

# ---------------------------------------------------------------------------
# CRITICO: reflexion sobre el campo dataSet de los Report Control Blocks.
#
# SclFileParser.relinkRcbDataSets() re-vincula por REFLEXION el campo de tipo
# DataSet de Urcb/Brcb, porque es package-private en iec61850bean y no hay API
# publica para tocarlo. Se necesita al fusionar varios AccessPoint de un mismo
# IED: SclParser solo resuelve las referencias dentro de un AccessPoint, y las
# cruzadas quedan en null, lo que hace que la libreria responda
# PARAMETER_VALUE_INAPPROPRIATE cuando el cliente llama retrieveModel().
#
# Si R8 renombra ese campo, findDataSetField() no lo encuentra, devuelve null y
# el re-vinculado se saltea EN SILENCIO: la app compila, arranca y sirve, pero
# los RCB de los archivos multi-AccessPoint quedan rotos. Es exactamente el tipo
# de falla muda que ya costo encontrar tres veces en este proyecto.
-keepclassmembers class com.beanit.iec61850bean.Rcb  { *; }
-keepclassmembers class com.beanit.iec61850bean.Urcb { *; }
-keepclassmembers class com.beanit.iec61850bean.Brcb { *; }
-keep class com.beanit.iec61850bean.DataSet { *; }

# La libreria completa: es un stack de protocolo con parseo por nombres de clase
# y ASN.1 generado; no vale la pena arriesgar a ofuscarla para ahorrar unos KB.
-keep class com.beanit.iec61850bean.** { *; }
-keep class com.beanit.asn1bean.** { *; }
-dontwarn com.beanit.**

# javax.xml / DOM que usa el pre-procesado de SclFileParser
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**

# Room genera implementaciones en tiempo de compilacion
-keep class com.ied.simulator.cache.** { *; }

# Mantener los nombres de linea para que un stack trace de produccion sirva
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

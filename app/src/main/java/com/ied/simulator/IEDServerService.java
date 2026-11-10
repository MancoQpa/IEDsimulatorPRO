package com.ied.simulator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaFloat32;
import com.beanit.iec61850bean.BdaFloat64;
import com.beanit.iec61850bean.BdaInt16;
import com.beanit.iec61850bean.BdaInt32;
import com.beanit.iec61850bean.BdaInt64;
import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.BdaVisibleString;
import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.beanit.iec61850bean.BdaCheck;
import com.beanit.iec61850bean.BdaTapCommand;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerEventListener;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServerSap;
import com.beanit.iec61850bean.ServiceError;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class IEDServerService extends Service implements ServerEventListener {

    private static final String TAG = "IEDServerService";
    private static final String CHANNEL_ID = "ied_server_channel";
    private static final int NOTIFICATION_ID = 1;

    // Actions
    public static final String ACTION_START = "com.ied.simulator.START";
    public static final String ACTION_STOP = "com.ied.simulator.STOP";

    // Broadcast actions
    public static final String ACTION_SERVER_STATUS = "com.ied.simulator.SERVER_STATUS";
    public static final String ACTION_DATA_CHANGED = "com.ied.simulator.DATA_CHANGED";
    public static final String ACTION_CLIENT_CONNECTED = "com.ied.simulator.CLIENT_CONNECTED";
    public static final String ACTION_CONTROL_REJECTED = "com.ied.simulator.CONTROL_REJECTED";
    public static final String ACTION_CONTROL_OK = "com.ied.simulator.CONTROL_OK";


    // Broadcast extras
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_NODE_REF = "node_ref";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_CLIENT_INFO = "client_info";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_MODEL_INDEX = "model_index";

    // Binder
    private final IBinder binder = new LocalBinder();

    // Motor de control: mantiene el estado de seleccion (SBO) entre mandos, asi
    // que tiene que vivir mientras viva el servidor.
    private final ControlEngine controlEngine = new ControlEngine();

    // Server components
    private ServerSap serverSap;
    private ServerModel serverModel;
    private boolean isRunning = false;
    private int serverPort = 102;

    // Wake lock to keep CPU running
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // Network monitoring
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    public class LocalBinder extends Binder {
        IEDServerService getService() {
            return IEDServerService.this;
        }
    }

    // ---- Traspaso del modelo ya parseado -----------------------------------
    //
    // La Activity parsea el SCL para mostrar el arbol, y hasta ahora el servicio
    // volvia a parsear EL MISMO archivo desde cero al arrancar: en un MiCOM de
    // 879 KB eran ~11 s en la Activity y otros ~7 s aca, para obtener lo mismo.
    //
    // Se resuelve pasandole el modelo ya construido. Es estatico a proposito:
    // el servicio todavia no existe cuando la Activity llama a
    // startForegroundService(), asi que no hay binder por donde entregarlo, y
    // esperar a onServiceConnected() agregaria una carrera innecesaria. Ambos
    // viven en el mismo proceso, asi que es un traspaso directo.
    //
    // Se limpia apenas se consume para no retener el modelo (son cientos de
    // miles de objetos en los SCD grandes).
    private static ServerModel modeloPreparseado;
    private static String uriPreparseada;

    /** La Activity ofrece el modelo que ya parseo, para evitar el doble parseo. */
    public static void ofrecerModeloPreparseado(Uri uri, ServerModel model) {
        uriPreparseada = (uri != null) ? uri.toString() : null;
        modeloPreparseado = model;
    }

    /** Toma el modelo ofrecido si corresponde a este archivo, y lo libera. */
    private static ServerModel tomarModeloPreparseado(Uri fileUri) {
        if (modeloPreparseado == null || uriPreparseada == null || fileUri == null) return null;
        if (!uriPreparseada.equals(fileUri.toString())) return null;
        ServerModel m = modeloPreparseado;
        modeloPreparseado = null;
        uriPreparseada = null;
        return m;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        setupNetworkCallback();

        // Acquire wake lock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "IEDSimulator::ServerWakeLock");
        }

        // Y ademas el candado de WiFi, que NO es lo mismo.
        //
        // El PARTIAL_WAKE_LOCK mantiene despierta la CPU; la radio WiFi se
        // duerme igual cuando deja de haber trafico. Para un cliente comun no
        // se nota: el es quien inicia las conexiones y al hacerlo despierta la
        // radio. Pero este equipo es un SERVIDOR -- espera que le hablen -- y
        // una radio dormida retrasa lo que entra.
        //
        // Asi se cayo una prueba contra un cliente real por WiFi: anduvo tres
        // minutos mientras el cliente leia cada 2 s, y se corto a los doce
        // segundos de que el cliente se quedara sin nodos que leer. El cliente
        // lo reporto como TIMEOUT_ERROR(22), que es "nadie contesto" y no "se
        // corto el cable": la asociacion seguia abierta y el equipo no
        // respondia. Ese es exactamente el cuadro de una radio en ahorro.
        //
        // HIGH_PERF mantiene la radio activa y sin agrupar paquetes.
        // LOW_LATENCY, desde API 29, ademas desactiva el ahorro de energia del
        // enlace; se prefiere cuando esta disponible.
        WifiManager wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            int modo = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wifiManager.createWifiLock(modo, "IEDSimulator::ServerWifiLock");
        }
    }

    private void setupNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Network available");
            }

            @Override
            public void onLost(Network network) {
                Log.w(TAG, "Network lost - stopping server");
                if (isRunning) {
                    stopServer();
                    broadcastStatus(false, "Conexión de red perdida");
                }
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Error registering network callback", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            Uri fileUri = intent.getData();
            int port = intent.getIntExtra(EXTRA_PORT, 10102);
            int modelIndex = intent.getIntExtra(EXTRA_MODEL_INDEX, 0);
            if (fileUri != null) {
                startServer(fileUri, port, modelIndex);
            }
        } else if (ACTION_STOP.equals(action)) {
            stopServer();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "IED Server",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("IED Simulator Server Status");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String status) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("IED Simulator")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void startServer(Uri fileUri, int port, int modelIndex) {
        if (isRunning) {
            Log.w(TAG, "Server already running");
            return;
        }

        this.serverPort = port;

        try {
            // Start foreground service
            startForeground(NOTIFICATION_ID, createNotification("Iniciando servidor..."));

            // Acquire wake lock
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(10 * 60 * 60 * 1000L); // 10 hours max
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
                Log.i(TAG, "WifiLock tomado: la radio no entra en ahorro mientras"
                        + " el servidor este arriba");
            }

            // Si la Activity ya parseo este mismo archivo, se reutiliza su modelo
            // en vez de rehacer el trabajo (ver ofrecerModeloPreparseado()).
            serverModel = tomarModeloPreparseado(fileUri);

            if (serverModel != null) {
                Log.i(TAG, "Modelo reutilizado de la Activity - se evita volver a parsear");
            } else {
                // Camino normal: el servicio arranco sin Activity delante (por
                // ejemplo tras recrearse el proceso), asi que hay que parsear.
                long t0 = System.currentTimeMillis();
                try (InputStream is = getContentResolver().openInputStream(fileUri)) {
                    // Recortar al IED pedido en vez de parsear el archivo entero:
                    // en un SCD de subestacion eso es construir decenas de equipos
                    // para usar uno.
                    List<ServerModel> models = SclFileParser.parseOnlyIed(is, modelIndex);
                    if (models == null || models.isEmpty()) {
                        throw new IOException("No server models found in SCL file");
                    }
                    serverModel = models.get(0);
                }
                Log.i(TAG, "Modelo parseado en el servicio: "
                        + (System.currentTimeMillis() - t0) + "ms");
            }

            // Dar valores validos a Mod/Beh/Health antes de publicar el modelo
            EstadoLocal.inicializarModoYSalud(serverModel);
            EstadoLocal.olvidarSimulacion();

            // Y dejar el "proceso" en un estado inicial coherente: sin
            // posiciones en intermedio, con calidad buena y con las banderas
            // que habilitan la maniobra. Va ANTES de crear el ServerSap para
            // que el primer cliente que lea ya vea un modelo operable.
            EstadoLocal.normalizar(serverModel, new ArrayList<BasicDataAttribute>());

            // Create server SAP on configured port
            serverSap = new ServerSap(port, 0, null, serverModel, null);

            // Start listening (pass ServerEventListener to startListening)
            serverSap.startListening(this);

            isRunning = true;
            Log.i(TAG, "IED Server started on port " + port);

            // Update notification
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, createNotification("Servidor en puerto " + port));
            }

            // Broadcast status
            broadcastStatus(true, "Servidor iniciado en puerto " + port);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start server", e);
            broadcastStatus(false, "Error: " + e.getMessage());
            stopSelf();
        }
    }

        public int getServerPort() {
        return serverPort;
    }

    private void stopServer() {
        final ServerSap sap = serverSap;
        serverSap = null;
        if (sap != null) {
            // ServerSap.stop() cierra las asociaciones abiertas y para eso le manda a cada
            // cliente el mensaje de desconexion por la red. stopServer() corre en el hilo
            // principal (boton "Detener", onDestroy, perdida de red) y Android prohibe la red
            // ahi: con un cliente conectado tiraba NetworkOnMainThreadException y la app se
            // cerraba (2026-11-09). Se detiene en otro hilo y se espera un poco, para que el
            // puerto quede libre si se inicia otro archivo enseguida.
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        sap.stop();
                    } catch (Throwable e) {
                        Log.w(TAG, "Error al detener el servidor", e);
                    }
                }
            }, "detener-servidor");
            t.start();
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) Log.w(TAG, "El servidor sigue cerrando conexiones despues de 3 s");
        }

        isRunning = false;

        // Release wake lock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        // Y soltar la radio: sostener el WifiLock con el servidor parado seria
        // gastarle la bateria al usuario sin motivo.
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }

        // Broadcast status
        broadcastStatus(false, "Servidor detenido");

        stopForeground(true);
        Log.i(TAG, "IED Server stopped");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public ServerModel getServerModel() {
        return serverModel;
    }

    /** Objetos que se pueden maniobrar a pie de equipo, para la ventana de control. */
    public List<EstadoLocal.Objeto> listarManiobrables() {
        return EstadoLocal.listarAparatos(serverModel);
    }

    /**
     * Maniobra local: abre o cierra el aparato como si se lo operara en sitio.
     *
     * No pasa por ControlEngine a proposito. Es la accion que en un equipo real
     * harian los contactos auxiliares del interruptor; aca la tiene que producir
     * la aplicacion porque no hay proceso del otro lado.
     */
    public boolean maniobraLocal(String ref, boolean cerrar) {
        if (serverModel == null) return false;
        List<BasicDataAttribute> cambiados = EstadoLocal.maniobrar(serverModel, ref, cerrar);
        if (cambiados.isEmpty()) return false;

        // Sincronico a proposito: no venimos de dentro de write(), y la ventana
        // se refresca apenas vuelve esta llamada. Si se publicara en otro hilo,
        // la lista mostraria todavia el estado anterior. Es setValues() el que
        // escribe el modelo y dispara los reportes por RCB.
        aplicarYPublicar(cambiados);
        for (BasicDataAttribute b : cambiados) {
            broadcastDataChanged(b.getReference().toString(), b.getValueString());
        }
        return true;
    }

    /** Posicion de la llave del aparato, o null si no declara Loc. */
    public Boolean enLocal(String ref) {
        return EstadoLocal.enLocal(serverModel, ref);
    }

    /** Gira la llave selectora local/remoto de un aparato. */
    public boolean ponerLlave(String ref, boolean local) {
        return publicarCambiosDe(EstadoLocal.ponerLlave(serverModel, ref, local));
    }

    /** Autoridad de mando vigente de un Logical Device. */
    public EstadoLocal.Autoridad autoridadDe(String ldName) {
        return EstadoLocal.autoridadDe(serverModel, ldName);
    }

    /** Mueve la autoridad de mando de todo un Logical Device. */
    public boolean ponerAutoridad(String ldName, EstadoLocal.Autoridad autoridad) {
        return publicarCambiosDe(EstadoLocal.ponerAutoridad(serverModel, ldName, autoridad));
    }

    /** Escribe los cambios en el modelo y avisa a la interfaz. */
    private boolean publicarCambiosDe(List<BasicDataAttribute> cambiados) {
        if (serverModel == null || cambiados == null || cambiados.isEmpty()) return false;
        aplicarYPublicar(cambiados);
        for (BasicDataAttribute b : cambiados) {
            broadcastDataChanged(b.getReference().toString(), b.getValueString());
        }
        return true;
    }

    /** Vuelve a dejar todos los aparatos abiertos, con calidad buena y hora nueva. */
    public int reiniciarEstado() {
        if (serverModel == null) return 0;
        int n = 0;
        for (EstadoLocal.Objeto o : EstadoLocal.listar(serverModel)) {
            List<BasicDataAttribute> cambiados =
                    EstadoLocal.maniobrar(serverModel, o.ref, false);
            if (cambiados.isEmpty()) continue;
            aplicarYPublicar(cambiados);
            n++;
        }
        return n;
    }

    /** Cambios del final de un mando diferido (aparato lento): modelo, reportes y pantalla. */
    private void publicarFinales(List<BasicDataAttribute> cambiados) {
        aplicarYPublicar(cambiados);
        for (BasicDataAttribute b : cambiados) {
            broadcastDataChanged(b.getReference().toString(), b.getValueString());
        }
    }

    /** Respuesta simulada de un aparato (5.5.0). */
    public EstadoLocal.Respuesta respuestaDe(String ref) {
        return EstadoLocal.respuestaDe(ref);
    }

    public void ponerRespuesta(String ref, EstadoLocal.Respuesta r) {
        EstadoLocal.ponerRespuesta(ref, r);
    }

    /** Sincronismo simulado de un interruptor (5.5.0). */
    public boolean sincronismoEnFalla(String ref) {
        return EstadoLocal.sincronismoEnFalla(ref);
    }

    public void ponerSincronismo(String ref, boolean falla) {
        EstadoLocal.ponerSincronismo(ref, falla);
    }

    /** Escribe los cambios en el modelo y emite los reportes, uno por uno. */
    private void aplicarYPublicar(List<BasicDataAttribute> cambiados) {
        ServerSap sap = serverSap;
        if (sap == null) return;
        for (BasicDataAttribute b : cambiados) {
            List<BasicDataAttribute> uno = new ArrayList<>();
            uno.add(b);
            try {
                sap.setValues(uno);
            } catch (Exception e) {
                Log.w(TAG, "setValues no pudo publicar " + b.getReference() + ": " + e);
            }
        }
    }

    /**
     * Set a data value in the model
     */
    public void setDataValue(String nodeRef, String value) {
        if (serverModel == null || !isRunning) {
            Log.w(TAG, "Cannot set value - server not running or model is null");
            return;
        }

        try {
            // Parse the reference (format: LD/LN.DO.DA or LD/LN$FC$DO$DA)
            Fc fc = extractFc(nodeRef);
            String cleanRef = cleanReference(nodeRef);

            Log.d(TAG, "Setting value - Original ref: " + nodeRef + ", Clean ref: " + cleanRef + ", FC: " + fc + ", Value: " + value);

            ModelNode node = serverModel.findModelNode(cleanRef, fc);

            // If not found with extracted FC, try common FCs
            if (node == null) {
                Log.d(TAG, "Node not found with FC=" + fc + ", trying other FCs...");
                Fc[] fcsToTry = {Fc.ST, Fc.MX, Fc.CO, Fc.CF, Fc.SP, Fc.SG};
                for (Fc tryFc : fcsToTry) {
                    if (tryFc != fc) {
                        node = serverModel.findModelNode(cleanRef, tryFc);
                        if (node != null) {
                            Log.d(TAG, "Found node with FC=" + tryFc);
                            fc = tryFc;
                            break;
                        }
                    }
                }
            }

            if (node instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) node;
                Log.d(TAG, "Found BDA type: " + bda.getClass().getSimpleName() + ", current value: " + bda.getValueString());

                setBasicDataAttributeValue(bda, value);

                // Notify clients. Puede fallar con NPE ("bdaMirror is null") si el
                // atributo no esta en ningun DataSet: el valor igual quedo escrito
                // en el modelo, solo no genera reporte.
                List<BasicDataAttribute> changedData = new ArrayList<>();
                changedData.add(bda);
                try {
                    serverSap.setValues(changedData);
                } catch (Exception e) {
                    Log.w(TAG, "setValues no pudo notificar " + cleanRef + ": " + e);
                }

                String newValue = bda.getValueString();
                Log.i(TAG, "Value set successfully: " + cleanRef + " [" + fc + "] = " + newValue);
                broadcastDataChanged(nodeRef, newValue);
            } else if (node != null) {
                Log.w(TAG, "Node found but not a BasicDataAttribute: " + node.getClass().getSimpleName());
            } else {
                Log.e(TAG, "Node not found for reference: " + cleanRef);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting value: " + nodeRef + " - " + e.getMessage(), e);
        }
    }

    private Fc extractFc(String nodeRef) {
        // Try to extract FC from reference
        // New format: "LD/LN.DO.attr$FC" (FC at end)
        // Old format: "LD/LN$FC$DO$DA" (FC after LN)
        if (nodeRef.contains("$")) {
            String[] parts = nodeRef.split("\\$");
            if (parts.length >= 2) {
                // Try the last part first (new format)
                String lastPart = parts[parts.length - 1];
                Fc fc = tryParseFc(lastPart);
                if (fc != null) {
                    return fc;
                }

                // Try old format (second part)
                fc = tryParseFc(parts[1]);
                if (fc != null) {
                    return fc;
                }
            }
        }
        return Fc.ST; // Default to ST
    }

    private Fc tryParseFc(String fcStr) {
        try {
            return Fc.valueOf(fcStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String cleanReference(String nodeRef) {
        // Convert reference to standard IEC 61850 format
        // New format: "LD/LN.DO.attr$FC" -> "LD/LN.DO.attr"
        // Old format: "LD/LN$FC$DO$DA" -> "LD/LN.DO.DA"
        if (nodeRef.contains("$")) {
            String[] parts = nodeRef.split("\\$");

            // Check if last part is FC (new format)
            String lastPart = parts[parts.length - 1];
            if (tryParseFc(lastPart) != null) {
                // It's the new format - just remove the $FC suffix
                return parts[0];
            }

            // Old format - convert "LD/LN$FC$DO$DA" to "LD/LN.DO.DA"
            if (parts.length >= 3) {
                StringBuilder sb = new StringBuilder(parts[0]);
                for (int i = 2; i < parts.length; i++) {
                    sb.append(".").append(parts[i]);
                }
                return sb.toString();
            }
        }
        return nodeRef;
    }

    private void setBasicDataAttributeValue(BasicDataAttribute bda, String value) {
        try {
            if (bda instanceof BdaBoolean) {
                ((BdaBoolean) bda).setValue(Boolean.parseBoolean(value) || "1".equals(value) || "true".equalsIgnoreCase(value));
            } else if (bda instanceof BdaInt8) {
                ((BdaInt8) bda).setValue(Byte.parseByte(value));
            } else if (bda instanceof BdaInt16) {
                ((BdaInt16) bda).setValue(Short.parseShort(value));
            } else if (bda instanceof BdaInt32) {
                ((BdaInt32) bda).setValue(Integer.parseInt(value));
            } else if (bda instanceof BdaInt64) {
                ((BdaInt64) bda).setValue(Long.parseLong(value));
            } else if (bda instanceof BdaFloat32) {
                ((BdaFloat32) bda).setFloat(Float.parseFloat(value));
            } else if (bda instanceof BdaFloat64) {
                ((BdaFloat64) bda).setDouble(Double.parseDouble(value));
            } else if (bda instanceof BdaVisibleString) {
                ((BdaVisibleString) bda).setValue(value);
            } else if (bda instanceof BdaDoubleBitPos) {
                // Double-bit position: 00=intermediate, 01=off, 10=on, 11=bad-state
                BdaDoubleBitPos dbpos = (BdaDoubleBitPos) bda;
                setDbposValue(dbpos, value);
            } else if (bda instanceof BdaCheck) {
                // Check type for control operations
                BdaCheck check = (BdaCheck) bda;
                // Parse value like "synchrocheck" or "interlockcheck" or boolean
                if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
                    check.setSynchrocheck(true);
                } else {
                    check.setSynchrocheck(false);
                    check.setInterlockCheck(false);
                }
            } else if (bda instanceof BdaTapCommand) {
                // Tap command for transformers
                BdaTapCommand tap = (BdaTapCommand) bda;
                setTapCommandValue(tap, value);
            } else {
                Log.w(TAG, "Unsupported BDA type: " + bda.getClass().getSimpleName() + " for ref: " + bda.getReference());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting value for " + bda.getReference() + ": " + e.getMessage());
        }
    }

    /**
     * Set Double-bit position value
     * Values: 00=intermediate-state, 01=off, 10=on, 11=bad-state
     * Or text: "off", "on", "intermediate", "bad"
     */
    private void setDbposValue(BdaDoubleBitPos dbpos, String value) {
        String lowerValue = value.toLowerCase().trim();

        // Handle text values
        if (lowerValue.equals("off") || lowerValue.equals("01") || lowerValue.equals("1")) {
            dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.OFF);
        } else if (lowerValue.equals("on") || lowerValue.equals("10") || lowerValue.equals("2")) {
            dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.ON);
        } else if (lowerValue.equals("intermediate") || lowerValue.equals("intermediate-state") || lowerValue.equals("00") || lowerValue.equals("0")) {
            dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE);
        } else if (lowerValue.equals("bad") || lowerValue.equals("bad-state") || lowerValue.equals("11") || lowerValue.equals("3")) {
            dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.BAD_STATE);
        } else {
            // Try to parse as number
            try {
                int intVal = Integer.parseInt(value);
                switch (intVal) {
                    case 0:
                        dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE);
                        break;
                    case 1:
                        dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.OFF);
                        break;
                    case 2:
                        dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.ON);
                        break;
                    case 3:
                        dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.BAD_STATE);
                        break;
                    default:
                        Log.w(TAG, "Invalid Dbpos value: " + value);
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Cannot parse Dbpos value: " + value);
            }
        }
        Log.i(TAG, "Set Dbpos value: " + dbpos.getValueString());
    }

    /**
     * Set Tap command value for transformers
     * Values: stop, lower, higher, reserved
     */
    private void setTapCommandValue(BdaTapCommand tap, String value) {
        String lowerValue = value.toLowerCase().trim();

        if (lowerValue.equals("stop") || lowerValue.equals("00") || lowerValue.equals("0")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.STOP);
        } else if (lowerValue.equals("lower") || lowerValue.equals("01") || lowerValue.equals("1")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.LOWER);
        } else if (lowerValue.equals("higher") || lowerValue.equals("10") || lowerValue.equals("2")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.HIGHER);
        } else if (lowerValue.equals("reserved") || lowerValue.equals("11") || lowerValue.equals("3")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.RESERVED);
        }
    }

    // ServerEventListener implementation

    @Override
    public void serverStoppedListening(ServerSap serverSap) {
        Log.i(TAG, "Server stopped listening");
        isRunning = false;
        broadcastStatus(false, "Servidor detenido");
    }

    @Override
    public List<ServiceError> write(List<BasicDataAttribute> bdas) {
        // Client is writing values
        Log.i(TAG, "Client writing " + bdas.size() + " values");

        // Select/Operate/Cancel llegan por aca: en iec61850bean son escrituras
        // MMS comunes a la estructura FC=CO, no un callback aparte.
        ControlEngine.Resultado ctl = controlEngine.procesar(serverModel, bdas);

        if (ctl.eraControl) {
            if (ctl.error != null) {
                Log.w(TAG, "Control RECHAZADO: " + ctl.error.getMessage());
                broadcastControlRejected(ctl.error.getMessage());

                // La lista tiene que traer UN elemento por cada atributo escrito.
                // La libreria solo interpreta el rechazo si el tamano coincide:
                //
                //   if (serviceErrors == null || serviceErrors.size() != bdas.size())
                //       -> lo da todo por exitoso
                //
                // Con la estructura de control completa llegando al listener,
                // devolver un solo error haria que el rechazo se ignorara EN
                // SILENCIO y el mando se aplicara igual.
                List<ServiceError> errors = new ArrayList<>();
                for (int i = 0; i < bdas.size(); i++) errors.add(ctl.error);
                return errors;
            }

            Log.i(TAG, "Control OK: " + ctl.descripcion);

            // 5.5.0: como termina el mando. La asociacion lo toma apenas vuelve este write().
            Terminacion.publicador = this::publicarFinales;
            Terminacion.anunciar(ctl.terminacion);

            // Publicar los cambios de estado que produjo el mando. Esto es lo que
            // hace que el cliente vea moverse el interruptor y que salgan los
            // reportes por RCB.
            if (!ctl.cambiados.isEmpty() && serverSap != null) {
                publicarCambios(ctl.cambiados);
                for (BasicDataAttribute b : ctl.cambiados) {
                    broadcastDataChanged(b.getReference().toString(), b.getValueString());
                }
            }
            broadcastControlOk(ctl.descripcion);
        }

        for (BasicDataAttribute bda : bdas) {
            String ref = bda.getReference().toString();
            String value = bda.getValueString();
            Log.d(TAG, "Write: " + ref + " = " + value);
            broadcastDataChanged(ref, value);
        }

        return null; // No errors
    }

    /**
     * Publica en el servidor los atributos que cambio un mando.
     *
     * Dos cosas que hay que hacer con cuidado, ambas verificadas contra un
     * cliente MMS real:
     *
     *  1. NO se puede llamar a setValues() dentro del propio write(): la
     *     libreria esta a mitad de codificar la respuesta de escritura y la
     *     asociacion se cae ("Error sending packet" / socket cerrado). El
     *     cliente pierde la conexion justo despues del primer mando aceptado.
     *     Por eso se difiere a otro hilo.
     *
     *  2. setValues() revienta con NullPointerException ("bdaMirror is null")
     *     cuando el atributo no pertenece a ningun DataSet, porque la libreria
     *     solo prepara el espejo de reporte para los que si. Se publica uno por
     *     uno para que un atributo sin espejo no impida publicar los demas.
     */
    private void publicarCambios(final List<BasicDataAttribute> cambiados) {
        final ServerSap sap = serverSap;
        if (sap == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                aplicarYPublicar(cambiados);
            }
        }, "publicar-cambios").start();
    }

    // Broadcast helpers

    private void broadcastStatus(boolean running, String message) {
        Intent intent = new Intent(ACTION_SERVER_STATUS);
        intent.putExtra(EXTRA_RUNNING, running);
        intent.putExtra(EXTRA_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastControlOk(String detalle) {
        Intent intent = new Intent(ACTION_CONTROL_OK);
        intent.putExtra(EXTRA_MESSAGE, detalle);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastControlRejected(String reason) {
        Intent intent = new Intent(ACTION_CONTROL_REJECTED);
        intent.putExtra(EXTRA_MESSAGE, reason);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastDataChanged(String nodeRef, String value) {
        Intent intent = new Intent(ACTION_DATA_CHANGED);
        intent.putExtra(EXTRA_NODE_REF, nodeRef);
        intent.putExtra(EXTRA_VALUE, value);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        // Unregister network callback
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering network callback", e);
            }
        }
        stopServer();
        super.onDestroy();
    }
}

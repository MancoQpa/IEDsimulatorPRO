package com.ied.simulator;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.beanit.iec61850bean.ServerModel;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "IEDSimulator";
    private static final int PERMISSION_REQUEST_CODE = 100;

    // UI Components
    private ImageButton btnAbout;
    private Button btnSelectFile;
    private Button btnStartStop;
    private TextView tvStatus;
    private TextView tvIpAddress;
    private TextView tvFileName;
    private TextView tvModelInfo;
    private RecyclerView rvDataModel;
    private EditText etPort;
    private View viewStatusIndicator;
    private View layoutLoading;
    private TextView tvLoadingMessage;
    private ImageButton btnExpandAll;
    private ImageButton btnCollapseAll;
    private ImageButton btnManiobra;

    // Server Service
    private IEDServerService serverService;
    private boolean serverServiceBound = false;
    private boolean isServerStopping = false;

    // Data
    private Uri selectedFileUri;
    private List<ServerModel> parsedModels;   // all IEDs found in the loaded SCL file
    private List<String> parsedIedNames;      // sus nombres segun el SCL, mismo orden
    private int selectedModelIndex = 0;       // indice dentro de parsedModels (hoy siempre 0)
    // Indice del IED DENTRO DEL ARCHIVO, que no es lo mismo: como ahora se parsea
    // solo el elegido, parsedModels tiene un unico elemento. El servicio necesita
    // este otro para poder recortar al IED correcto si le toca parsear por su
    // cuenta; con el indice interno cargaria siempre el primero del archivo.
    private int selectedIedIndex = 0;
    private ModelTreeAdapter modelAdapter;
    private ServerModel loadedModel;

    // Cache manager for SCL models (persistent "virtual memory")
    private ModelCacheManager modelCacheManager;

    // Threading
    // El parseo de un SCL es trabajo pesado de CPU. Android manda por defecto los
    // hilos de fondo a los nucleos de baja potencia, asi que se le pide prioridad
    // de primer plano: el usuario esta esperando mirando la pantalla.
    /**
     * Limpiezas que tienen que correr si la Activity muere con ventanas abiertas:
     * dar de baja receptores y cancelar callbacks pendientes. Sin esto, un receptor
     * de un dialogo sobrevivia a la Activity y terminaba tumbando el proceso.
     */
    private final java.util.LinkedHashMap<Object, Runnable> limpiezas =
            new java.util.LinkedHashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scl-worker");
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Indicador de carga: el mensaje, los segundos transcurridos y, si el archivo es un SCD,
    // por que puede tardar. Hasta 2026-10-21 era solo "Cargando archivo SCL...", sin nada que
    // distinguiera "procesando" de "colgado" con un archivo de subestacion entera.
    private String mensajeCarga = "";
    private long inicioCarga;
    private volatile boolean archivoEsScd;   // el archivo que se esta cargando es .scd (lo fija el hilo de carga)
    private boolean avisoScdVisible;   // mostrar la explicacion en el indicador actual
    private final Runnable relojCarga = new Runnable() {
        @Override public void run() {
            pintarCarga();
            mainHandler.postDelayed(this, 1000);
        }
    };

    // File picker launcher
    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedFileUri = result.getData().getData();
                    if (selectedFileUri != null) {
                        loadSclFileAsync(selectedFileUri);
                    }
                }
            });

    // Broadcast receiver for server updates
    private final BroadcastReceiver serverUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (IEDServerService.ACTION_SERVER_STATUS.equals(action)) {
                boolean running = intent.getBooleanExtra(IEDServerService.EXTRA_RUNNING, false);
                String message = intent.getStringExtra(IEDServerService.EXTRA_MESSAGE);
                updateServerStatus(running, message);
            } else if (IEDServerService.ACTION_DATA_CHANGED.equals(action)) {
                String nodeRef = intent.getStringExtra(IEDServerService.EXTRA_NODE_REF);
                String value = intent.getStringExtra(IEDServerService.EXTRA_VALUE);
                onDataChanged(nodeRef, value);
            } else if (IEDServerService.ACTION_CLIENT_CONNECTED.equals(action)) {
                String clientInfo = intent.getStringExtra(IEDServerService.EXTRA_CLIENT_INFO);
                Toast.makeText(context, "Cliente conectado: " + clientInfo, Toast.LENGTH_SHORT).show();
            }
        }
    };

    // Server service connection
    private final ServiceConnection serverServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IEDServerService.LocalBinder localBinder = (IEDServerService.LocalBinder) binder;
            serverService = localBinder.getService();
            serverServiceBound = true;
            isServerStopping = false;
            updateUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serverServiceBound = false;
            serverService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize model cache manager (persistent "virtual memory" for SCL models)
        modelCacheManager = new ModelCacheManager(this);

        // Las versiones anteriores cacheaban la estructura del modelo y nunca la leian.
        // Con un solo archivo real eso dejaba una base de 413 MB en el telefono. Se la
        // borra una vez, para no dejarle al usuario medio giga de basura.
        executor.execute(() -> {
            try {
                java.io.File db = getDatabasePath("model_cache.db");
                if (db != null && db.exists()) {
                    long mb = db.length() / (1024 * 1024);
                    if (deleteDatabase("model_cache.db")) {
                        Log.i(TAG, "Cache obsoleto borrado: se recuperaron ~" + mb + " MB");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "No se pudo borrar el cache obsoleto", e);
            }
        });

        // Log cache stats in background (Room requires background thread)
        executor.execute(() -> {
            try {
                String stats = modelCacheManager.getCacheStats();
                Log.i(TAG, "Model cache: " + stats);
            } catch (Exception e) {
                Log.w(TAG, "Could not get cache stats", e);
            }
        });

        initViews();
        setupListeners();
        checkPermissions();
        displayIpAddress();

        configurarBotonAtras();

        // Handle intent (file opened from file manager)
        handleIntent(getIntent());
    }

    /**
     * El boton atras cerraba la aplicacion desde la pantalla principal sin preguntar.
     * No habia ningun manejo de atras: una sola Activity, el resto dialogos, y en la
     * pantalla principal Android terminaba la Activity.
     *
     * Con el servidor en marcha eso es peligroso. El servidor vive en un foreground
     * service, asi que sobrevive al cierre de la interfaz y queda atendiendo clientes
     * MMS detras de una notificacion, sin pantalla desde donde verlo ni detenerlo. Y
     * el 2026-08-31 la Activity destruida dejo ademas un receptor vivo que tumbo el
     * proceso entero -- con el servidor dentro -- al primer cambio de dato.
     *
     * Con el servidor parado no se pregunta nada: atras sale, como espera cualquiera.
     */
    private void configurarBotonAtras() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!(serverServiceBound && serverService != null && serverService.isRunning())) {
                    setEnabled(false);                       // dejar pasar el atras normal
                    getOnBackPressedDispatcher().onBackPressed();
                    return;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.salir_titulo)
                        .setMessage(R.string.salir_mensaje)
                        .setPositiveButton(R.string.salir_detener, (d, w) ->
                                stopServerSafely(() -> finish()))
                        .setNeutralButton(R.string.salir_dejar, (d, w) -> moveTaskToBack(true))
                        .setNegativeButton(R.string.salir_cancelar, null)
                        .show();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null) {
                selectedFileUri = uri;
                loadSclFileAsync(uri);
            }
        }
    }

    private void initViews() {
        // Header (el titulo es fijo en el layout: header_server)
        btnAbout = findViewById(R.id.btn_about);

        // Main controls
        btnSelectFile = findViewById(R.id.btn_select_file);
        btnStartStop = findViewById(R.id.btn_start_stop);
        tvStatus = findViewById(R.id.tv_status);
        tvIpAddress = findViewById(R.id.tv_ip_address);
        tvFileName = findViewById(R.id.tv_file_name);
        tvModelInfo = findViewById(R.id.tv_model_info);
        rvDataModel = findViewById(R.id.rv_data_model);
        etPort = findViewById(R.id.et_port);
        viewStatusIndicator = findViewById(R.id.view_status_indicator);

        // Loading
        layoutLoading = findViewById(R.id.layout_loading);
        tvLoadingMessage = findViewById(R.id.tv_loading_message);

        // Tree controls
        btnExpandAll = findViewById(R.id.btn_expand_all);
        btnCollapseAll = findViewById(R.id.btn_collapse_all);
        btnManiobra = findViewById(R.id.btn_maniobra);

        // Setup RecyclerView for Model
        modelAdapter = new ModelTreeAdapter(this, new ArrayList<>());
        modelAdapter.setOnValueChangeListener(this::onUserChangedValue);
        rvDataModel.setLayoutManager(new LinearLayoutManager(this));
        rvDataModel.setAdapter(modelAdapter);

        // Setup Simulation Mode switch
        SwitchMaterial switchSimulationMode = findViewById(R.id.switch_simulation_mode);
        switchSimulationMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ModelTreeAdapter.setSimulationMode(isChecked);
            // Reload tree to update editable state
            if (loadedModel != null) {
                displayModelInfo(loadedModel);
            }
        });
    }

    private void setupListeners() {
        btnSelectFile.setOnClickListener(v -> openFilePicker());
        btnStartStop.setOnClickListener(v -> toggleServer());
        btnAbout.setOnClickListener(v -> showAboutDialog());

        // Tree expand/collapse buttons
        btnExpandAll.setOnClickListener(v -> operarArbol(R.string.arbol_expandiendo, true));
        btnCollapseAll.setOnClickListener(v -> operarArbol(R.string.arbol_colapsando, false));
        btnManiobra.setOnClickListener(v -> mostrarVentanaManiobra());

        // Update IP display when port changes
        etPort.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                displayIpAddress();
            }
        });

    }

    /**
     * Ventana de maniobra local.
     *
     * El simulador corre en un telefono: no tiene los contactos auxiliares del
     * interruptor que en un equipo real definen la posicion. Esta ventana ocupa
     * ese lugar. Sirve para dejar el aparato en un estado inicial valido
     * (abierto o cerrado, con calidad buena) ANTES de probar el control desde
     * el cliente, que es lo que hace que la prueba signifique algo.
     *
     * Ojo: abrir/cerrar desde aca NO pasa por ControlEngine. Es deliberado: es
     * la maniobra fisica, no un mando. El mando se prueba desde el cliente.
     */
    private void mostrarVentanaManiobra() {
        if (!serverServiceBound || serverService == null || !serverService.isRunning()) {
            Toast.makeText(this, R.string.maniobra_servidor_parado, Toast.LENGTH_LONG).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AboutDialogTheme);
        View vista = LayoutInflater.from(this).inflate(R.layout.dialog_maniobra, null);
        builder.setView(vista);
        AlertDialog dialog = builder.create();

        LinearLayout contenedor = vista.findViewById(R.id.contenedor_maniobra);
        TextView vacio = vista.findViewById(R.id.tv_maniobra_vacio);

        final android.widget.CheckBox chkVerTodas = vista.findViewById(R.id.chk_ver_todas);

        // Por defecto se listan solo los aparatos de maniobra. Un archivo real
        // trae decenas de booleanos sueltos -- CmdBlk, LocSta, PosA/PosB/PosC --
        // que sepultaban al interruptor y a los dos seccionadores que el usuario
        // venia a buscar. El resto sigue accesible, pero no estorba.
        final View panelCarga = vista.findViewById(R.id.layout_maniobra_cargando);
        final TextView tvCarga = vista.findViewById(R.id.tv_maniobra_cargando);

        Runnable refrescar = new Runnable() {
            @Override public void run() {
                final Runnable self = this;
                conIndicador(panelCarga, tvCarga, getString(R.string.maniobra_cargando_lista),
                        () -> serverService.listarManiobrables(),
                        objetos -> pintarLista(objetos, contenedor, vacio, chkVerTodas, self));
            }
        };
        chkVerTodas.setOnCheckedChangeListener((b, v) -> refrescar.run());
        refrescar.run();

        // La ventana se refrescaba solo al apretar sus propios botones. Si el
        // aparato se movia por otro camino -- el arbol, un cliente MMS conectado,
        // o una posicion vinculada que arrastra a las demas -- la lista quedaba
        // mostrando el estado anterior. El servicio ya emite ACTION_DATA_CHANGED
        // en cada cambio; aca simplemente se lo escucha mientras el dialogo vive.
        final android.os.Handler handler = new android.os.Handler(getMainLooper());
        final Runnable refrescoDiferido = refrescar;
        final BroadcastReceiver cambios = new BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                // Una sola maniobra emite varios avisos (stVal, q, t, y las
                // posiciones vinculadas). Se agrupan para no reconstruir la
                // lista cuatro veces seguidas y que parpadee.
                handler.removeCallbacks(refrescoDiferido);
                handler.postDelayed(refrescoDiferido, 120);
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(cambios,
                new IntentFilter(IEDServerService.ACTION_DATA_CHANGED));
        // La baja del receptor colgaba solo del cierre del dialogo. Si la Activity
        // moria con el dialogo abierto, el receptor seguia escuchando y disparaba
        // contra una Activity muerta. Ahora la misma limpieza corre por los dos
        // caminos, y se ejecuta una sola vez.
        final boolean[] limpiado = {false};
        final Runnable limpiar = () -> {
            if (limpiado[0]) return;
            limpiado[0] = true;
            handler.removeCallbacks(refrescoDiferido);
            LocalBroadcastManager.getInstance(this).unregisterReceiver(cambios);
            limpiezas.remove(limpiado);
        };
        limpiezas.put(limpiado, limpiar);
        dialog.setOnDismissListener(d -> limpiar.run());

        final Runnable refrescarFinal = refrescar;
        vista.findViewById(R.id.btn_tiempos).setOnClickListener(v -> mostrarTiempos());

        // Modo del IED (5.7.0): el LLN0.Mod de todos los LD; el Beh de cada LN se deriva de ahi.
        {
            final Button bOn = vista.findViewById(R.id.btn_modo_on);
            final Button bTest = vista.findViewById(R.id.btn_modo_test);
            final TextView tvEf = vista.findViewById(R.id.tv_modo_efecto);
            final Runnable pintarModo = () -> {
                Integer m = serverService.modoIed();
                boolean test = m != null && (m == EstadoLocal.MODO_TEST || m == EstadoLocal.MODO_TEST_BLOQUEADO);
                boolean on = m != null && m == EstadoLocal.MODO_ON;
                int encendido = getResources().getColor(R.color.brand_amber);
                int apagado = getResources().getColor(R.color.text_secondary);
                bOn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(on ? encendido : apagado));
                bTest.setBackgroundTintList(android.content.res.ColorStateList.valueOf(test ? encendido : apagado));
                tvEf.setText(test ? R.string.modo_ef_test : on ? R.string.modo_ef_on : R.string.modo_ef_mixto);
            };
            if (serverService.modoIed() == null && !hayModoEnAlgunLd()) {
                vista.findViewById(R.id.tv_modo_titulo).setVisibility(View.GONE);
                vista.findViewById(R.id.fila_modo).setVisibility(View.GONE);
                tvEf.setVisibility(View.GONE);
            } else {
                pintarModo.run();
                bOn.setOnClickListener(v -> {
                    serverService.ponerModoIed(EstadoLocal.MODO_ON);
                    pintarModo.run(); refrescarFinal.run();
                });
                bTest.setOnClickListener(v -> {
                    serverService.ponerModoIed(EstadoLocal.MODO_TEST);
                    pintarModo.run(); refrescarFinal.run();
                });
            }
        }

        // Selector de autoridad del Logical Device. Es la jerarquia de arriba de
        // la llave por aparato: la llave dice si ESE equipo se opera a pie de
        // maquina, y esto dice desde donde se puede mandar cuando no lo esta.
        // Se toma el LD del primer aparato: en un archivo por IED todos los de
        // maniobra viven en el mismo.
        {
            List<EstadoLocal.Objeto> hay = serverService.listarManiobrables();
            View filaAut = vista.findViewById(R.id.fila_autoridad);
            TextView tvAutTit = vista.findViewById(R.id.tv_autoridad_titulo);
            TextView tvAutEf = vista.findViewById(R.id.tv_autoridad_efecto);
            if (hay.isEmpty()) {
                filaAut.setVisibility(View.GONE);
                tvAutTit.setVisibility(View.GONE);
                tvAutEf.setVisibility(View.GONE);
            } else {
                final String ldAut = hay.get(0).ld;
                final Button bL = vista.findViewById(R.id.btn_aut_local);
                final Button bB = vista.findViewById(R.id.btn_aut_bahia);
                final Button bE = vista.findViewById(R.id.btn_aut_estacion);
                final Button bR = vista.findViewById(R.id.btn_aut_remoto);
                final Runnable pintarAut = new Runnable() {
                    @Override public void run() {
                        EstadoLocal.Autoridad a = serverService.autoridadDe(ldAut);
                        int on = getResources().getColor(R.color.brand_amber);
                        int off = getResources().getColor(R.color.text_secondary);
                        bL.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                                a == EstadoLocal.Autoridad.LOCAL ? on : off));
                        bB.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                                a == EstadoLocal.Autoridad.BAHIA ? on : off));
                        bE.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                                a == EstadoLocal.Autoridad.ESTACION ? on : off));
                        bR.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                                a == EstadoLocal.Autoridad.REMOTO ? on : off));
                        tvAutEf.setText(a == EstadoLocal.Autoridad.LOCAL
                                ? R.string.autoridad_ef_local
                                : a == EstadoLocal.Autoridad.BAHIA
                                ? R.string.autoridad_ef_bahia
                                : a == EstadoLocal.Autoridad.ESTACION
                                ? R.string.autoridad_ef_estacion
                                : R.string.autoridad_ef_remoto);
                    }
                };
                pintarAut.run();
                bL.setOnClickListener(v -> {
                    serverService.ponerAutoridad(ldAut, EstadoLocal.Autoridad.LOCAL);
                    pintarAut.run(); refrescarFinal.run();
                });
                bB.setOnClickListener(v -> {
                    serverService.ponerAutoridad(ldAut, EstadoLocal.Autoridad.BAHIA);
                    pintarAut.run(); refrescarFinal.run();
                });
                bE.setOnClickListener(v -> {
                    serverService.ponerAutoridad(ldAut, EstadoLocal.Autoridad.ESTACION);
                    pintarAut.run(); refrescarFinal.run();
                });
                bR.setOnClickListener(v -> {
                    serverService.ponerAutoridad(ldAut, EstadoLocal.Autoridad.REMOTO);
                    pintarAut.run(); refrescarFinal.run();
                });
            }
        }

        vista.findViewById(R.id.btn_reiniciar_estado).setOnClickListener(v ->
            conIndicador(panelCarga, tvCarga, getString(R.string.maniobra_cargando_reinicio),
                    () -> serverService.reiniciarEstado(),
                    n -> {
                        Toast.makeText(this,
                                getString(R.string.maniobra_reiniciado, n),
                                Toast.LENGTH_SHORT).show();
                        refrescarFinal.run();
                    }));
        vista.findViewById(R.id.btn_maniobra_cerrar).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /** Arma las filas de la ventana de maniobra. Corre en el hilo de interfaz. */
    private void pintarLista(List<EstadoLocal.Objeto> objetos, LinearLayout contenedor,
                             TextView vacio, android.widget.CheckBox chkVerTodas,
                             Runnable refrescar) {
        contenedor.removeAllViews();

        int otras = 0;
        for (EstadoLocal.Objeto o : objetos) if (!o.esAparato) otras++;
        chkVerTodas.setText(chkVerTodas.isChecked()
                ? getString(R.string.maniobra_solo_aparatos)
                : getString(R.string.maniobra_ver_todas, otras));
        chkVerTodas.setVisibility(otras == 0 ? View.GONE : View.VISIBLE);

        final boolean verTodas = chkVerTodas.isChecked();
        int mostrados = 0;
        boolean separador = false;
        for (final EstadoLocal.Objeto o : objetos) {
            if (!o.esAparato && !verTodas) continue;
            if (!o.esAparato && !separador) {
                contenedor.addView(encabezadoSeccion(getString(R.string.maniobra_otras_senales)));
                separador = true;
            }
            contenedor.addView(filaManiobra(o, refrescar));
            mostrados++;
        }
        vacio.setVisibility(mostrados == 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * Expande o colapsa el arbol completo, fuera del hilo de interfaz.
     *
     * Con un SCD de subestacion son mas de cien mil nodos y bloqueaba la pantalla
     * varios segundos, mas que cualquier otra operacion de la aplicacion.
     *
     * Se usa showLoading() y no el indicador con retardo de la ventana de
     * maniobra, y la diferencia no es estetica: showLoading() OCULTA el
     * RecyclerView. El trabajo modifica las mismas listas que el RecyclerView
     * lee para dibujar, y esas estructuras no son seguras entre hilos; mientras
     * el panel de carga esta arriba, la lista no se esta pintando y la
     * modificacion es segura. Por eso tampoco se espera el umbral de 400 ms:
     * durante esa espera el RecyclerView seguiria visible sobre datos que ya
     * estarian cambiando.
     */
    private void operarArbol(int mensajeRes, boolean expandir) {
        if (modelAdapter == null) return;
        showLoading(getString(mensajeRes));
        executor.execute(() -> {
            long t0 = System.currentTimeMillis();
            if (expandir) modelAdapter.prepararExpandirTodo();
            else          modelAdapter.prepararColapsarTodo();
            final long ms = System.currentTimeMillis() - t0;
            runOnUiThread(() -> {
                modelAdapter.notifyDataSetChanged();
                hideLoading();
                Log.i(TAG, (expandir ? "Expandir" : "Colapsar") + " arbol: " + ms + " ms");
            });
        });
    }

    // ── Trabajo largo con indicador ──────────────────────────────────────────
    //
    // Varias operaciones sobre el modelo -- listar los aparatos, reiniciar su
    // estado -- recorren miles de nodos y corrian en el hilo de interfaz. La
    // ventana quedaba congelada sin ninguna señal, y con un CID pesado el usuario
    // no tiene forma de distinguir "esta trabajando" de "se colgo": la tentacion
    // es cerrar la aplicacion. Ademas, mientras el hilo esta bloqueado ningun
    // indicador alcanza a dibujarse, asi que no basta con agregar el simbolo:
    // hay que sacar el trabajo del hilo.

    /**
     * Cuanto se espera antes de mostrar el indicador. Por debajo no se muestra.
     *
     * 400 ms y no 1 s: la sensacion de "esto se colgo" empieza bastante antes del
     * segundo, y el problema que se quiere evitar es que el usuario cierre la
     * aplicacion creyendo que se trabo. Por debajo de este umbral la operacion se
     * percibe instantanea y el indicador solo agregaria ruido.
     */
    private static final long INDICADOR_RETARDO_MS = 400;
    /** Una vez visible, se mantiene al menos esto, para que no parpadee. */
    private static final long INDICADOR_MINIMO_MS = 400;

    private interface TareaLarga<T> { T ejecutar(); }
    private interface AlTerminar<T> { void con(T resultado); }

    /**
     * Corre la tarea fuera del hilo de interfaz y muestra el indicador si tarda.
     *
     * @param panel    contenedor del indicador (se muestra y se oculta entero)
     * @param etiqueta texto que describe qué se está haciendo
     */
    private <T> void conIndicador(final View panel, final TextView etiquetaView,
                                  final String etiqueta, final TareaLarga<T> tarea,
                                  final AlTerminar<T> alTerminar) {
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final long[] mostradoEn = {0};
        final boolean[] terminado = {false};

        final Runnable mostrar = () -> {
            if (terminado[0]) return;
            etiquetaView.setText(etiqueta);
            panel.setVisibility(View.VISIBLE);
            mostradoEn[0] = System.currentTimeMillis();
        };
        h.postDelayed(mostrar, INDICADOR_RETARDO_MS);

        // La Activity puede haber muerto con trabajo todavia en camino: onDestroy
        // cierra el executor, y mandarle una tarea lanza RejectedExecutionException,
        // que en Android se lleva puesto el proceso entero -- y con el, el servidor.
        // Paso el 2026-08-31 al escribir Loc.stVal con la ventana de maniobra abierta
        // detras de una Activity ya destruida por el boton atras.
        if (executor.isShutdown()) {
            h.removeCallbacks(mostrar);
            return;
        }

        executor.execute(() -> {
            final T resultado = tarea.ejecutar();
            h.post(() -> {
                terminado[0] = true;
                h.removeCallbacks(mostrar);
                long visible = mostradoEn[0] == 0 ? -1 : System.currentTimeMillis() - mostradoEn[0];
                if (visible >= 0 && visible < INDICADOR_MINIMO_MS) {
                    // Se alcanzo a mostrar por muy poco: se sostiene el resto del
                    // minimo para que el usuario lo vea y no quede un destello.
                    h.postDelayed(() -> {
                        panel.setVisibility(View.GONE);
                        alTerminar.con(resultado);
                    }, INDICADOR_MINIMO_MS - visible);
                } else {
                    panel.setVisibility(View.GONE);
                    alTerminar.con(resultado);
                }
            });
        });
    }

    /** Titulo gris que separa los aparatos de las señales sueltas. */
    private View encabezadoSeccion(String texto) {
        TextView t = new TextView(this);
        t.setText(texto);
        t.setTextSize(11f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(getResources().getColor(R.color.text_secondary));
        int p = (int) (10 * getResources().getDisplayMetrics().density);
        t.setPadding(p, p, p, p / 2);
        return t;
    }

    /** Una fila de la ventana de maniobra: estado actual + botones ABRIR/CERRAR. */
    private View filaManiobra(final EstadoLocal.Objeto o, final Runnable refrescar) {
        View fila = LayoutInflater.from(this).inflate(R.layout.item_maniobra, null);

        ((TextView) fila.findViewById(R.id.tv_obj_nombre)).setText(o.nombre);
        // El subtitulo dice de que aparato se trata y quien lo comanda. Antes
        // mostraba "sin mando (status-only)" al lado de dos botones que si
        // funcionan: el ctlModel describe el mando por MMS, y estos botones
        // hacen maniobra local, que no pasa por ahi. Se aclara cual es cual.
        StringBuilder sub = new StringBuilder(o.ld);
        if (!o.comandadoPor.isEmpty()) {
            sub.append("   ·   ").append(getString(R.string.maniobra_comandado_por))
               .append(" ").append(o.comandadoPor);
        }
        sub.append("   ·   ").append(o.ctlModel == 0
                ? getString(R.string.maniobra_sin_mando_mms)
                : getString(R.string.maniobra_mando_mms, descripcionCtlModel(o.ctlModel)));
        ((TextView) fila.findViewById(R.id.tv_obj_ld)).setText(sub.toString());

        TextView tvEstado = fila.findViewById(R.id.tv_obj_estado);
        tvEstado.setText(o.estado + "   (q: " + o.calidad + ")");
        tvEstado.setTextColor(getResources().getColor(
                "CERRADO".equals(o.estado) ? R.color.status_running
                        : "ABIERTO".equals(o.estado) ? R.color.status_stopped
                        : R.color.brand_amber));

        // El simbolo acompaña al texto, no lo reemplaza: el color solo es
        // ambiguo y ademas no distinguia INTERMEDIO de INVALIDO.
        ((SwitchSymbolView) fila.findViewById(R.id.sym_obj_estado))
                .setObjeto(o.estado, o.nombre, o.admiteMando());

        // Llave selectora del aparato. Es herraje: en LOCAL el aparato rechaza toda
        // orden por comunicaciones (Loc y LocKey en true). Los pulsadores de abajo
        // son a pie de equipo y funcionan en cualquier posicion de la llave.
        Boolean local = serverService.enLocal(o.ref);
        View filaLlave = fila.findViewById(R.id.fila_llave);
        if (local == null) {
            filaLlave.setVisibility(View.GONE);   // el LN no declara Loc
        } else {
            Button bl = fila.findViewById(R.id.btn_llave_local);
            Button br = fila.findViewById(R.id.btn_llave_remoto);
            pintarPar(bl, br, local);
            bl.setOnClickListener(v -> { serverService.ponerLlave(o.ref, true); refrescar.run(); });
            br.setOnClickListener(v -> { serverService.ponerLlave(o.ref, false); refrescar.run(); });
        }

        // Respuesta del aparato y sincronismo (5.5.0): estados del proceso que el operador elige.
        View filaResp = fila.findViewById(R.id.fila_respuesta);
        if (!o.esAparato || !o.doblePunto) {
            filaResp.setVisibility(View.GONE);
        } else {
            Button bResp = fila.findViewById(R.id.btn_respuesta);
            EstadoLocal.Respuesta r = serverService.respuestaDe(o.ref);
            bResp.setText(textoRespuesta(r));
            int on = getResources().getColor(R.color.brand_amber);
            int off = getResources().getColor(R.color.text_secondary);
            bResp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    r == EstadoLocal.Respuesta.NORMAL ? off : on));
            bResp.setOnClickListener(v -> {
                EstadoLocal.Respuesta[] todas = EstadoLocal.Respuesta.values();
                EstadoLocal.Respuesta sig = todas[(serverService.respuestaDe(o.ref).ordinal() + 1) % todas.length];
                serverService.ponerRespuesta(o.ref, sig);
                refrescar.run();
            });
            Button bSync = fila.findViewById(R.id.btn_sincronismo);
            if (!o.nombre.contains("XCBR")) {
                bSync.setVisibility(View.GONE);
            } else {
                boolean falla = serverService.sincronismoEnFalla(o.ref);
                bSync.setText(falla ? R.string.sincronismo_falla : R.string.sincronismo_ok);
                bSync.setBackgroundTintList(android.content.res.ColorStateList.valueOf(falla ? on : off));
                bSync.setOnClickListener(v -> {
                    serverService.ponerSincronismo(o.ref, !serverService.sincronismoEnFalla(o.ref));
                    refrescar.run();
                });
            }
        }

        // Mecanismo y gas (5.6.0): solo en interruptores, que son los que tienen resorte y SF6.
        View filaMec = fila.findViewById(R.id.fila_mecanismo);
        View filaSf6 = fila.findViewById(R.id.fila_sf6);
        if (!o.esAparato || !o.doblePunto || !o.nombre.contains("XCBR")) {
            filaMec.setVisibility(View.GONE);
            filaSf6.setVisibility(View.GONE);
        } else {
            int on = getResources().getColor(R.color.brand_amber);
            int off = getResources().getColor(R.color.text_secondary);
            Button bMec = fila.findViewById(R.id.btn_mecanismo);
            boolean fa = serverService.fallaApertura(o.ref), fc = serverService.fallaCierre(o.ref);
            bMec.setText(fa && fc ? R.string.mecanismo_falla_ambas
                    : fa ? R.string.mecanismo_falla_apertura
                    : fc ? R.string.mecanismo_falla_cierre
                    : R.string.mecanismo_ok);
            bMec.setBackgroundTintList(android.content.res.ColorStateList.valueOf(fa || fc ? on : off));
            bMec.setOnClickListener(v -> {
                // OK -> falla de apertura -> falla de cierre -> las dos -> OK
                boolean a = serverService.fallaApertura(o.ref), c = serverService.fallaCierre(o.ref);
                int paso = (a ? 1 : 0) + (c ? 2 : 0);
                int sig = paso == 0 ? 1 : paso == 1 ? 2 : paso == 2 ? 3 : 0;
                serverService.ponerFallaApertura(o.ref, (sig & 1) != 0);
                serverService.ponerFallaCierre(o.ref, (sig & 2) != 0);
                refrescar.run();
            });

            Button bRes = fila.findViewById(R.id.btn_resorte);
            boolean desc = serverService.resorteDescargado(o.ref);
            bRes.setText(desc ? R.string.resorte_descargado : R.string.resorte_cargado);
            bRes.setBackgroundTintList(android.content.res.ColorStateList.valueOf(desc ? on : off));
            bRes.setOnClickListener(v -> {
                serverService.ponerResorteDescargado(o.ref, !serverService.resorteDescargado(o.ref));
                refrescar.run();
            });

            Button bSf6 = fila.findViewById(R.id.btn_sf6);
            EstadoLocal.Sf6 gas = serverService.sf6De(o.ref);
            bSf6.setText(gas == EstadoLocal.Sf6.BLOQUEO ? R.string.sf6_bloqueo
                    : gas == EstadoLocal.Sf6.ALARMA ? R.string.sf6_alarma
                    : R.string.sf6_normal);
            bSf6.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    gas == EstadoLocal.Sf6.NORMAL ? off : on));
            bSf6.setOnClickListener(v -> {
                EstadoLocal.Sf6[] todos = EstadoLocal.Sf6.values();
                serverService.ponerSf6(o.ref, todos[(serverService.sf6De(o.ref).ordinal() + 1) % todos.length]);
                refrescar.run();
            });
        }

        fila.findViewById(R.id.btn_obj_abrir).setOnClickListener(v -> {
            maniobrarAPie(o.ref, false);
            refrescar.run();
        });
        fila.findViewById(R.id.btn_obj_cerrar).setOnClickListener(v -> {
            maniobrarAPie(o.ref, true);
            refrescar.run();
        });
        return fila;
    }

    /** true si algun LD declara LLN0.Mod (si no, el modo del IED no se puede cambiar). */
    private boolean hayModoEnAlgunLd() {
        com.beanit.iec61850bean.ServerModel m = serverService.getServerModel();
        if (m == null) return false;
        for (com.beanit.iec61850bean.ModelNode ld : m.getChildren()) {
            if (m.findModelNode(ld.getName() + "/LLN0.Mod.stVal", com.beanit.iec61850bean.Fc.ST) != null) return true;
        }
        return false;
    }

    /** Pulsador a pie de equipo: si el mecanismo o el gas lo impiden, se dice por que. */
    private void maniobrarAPie(String ref, boolean cerrar) {
        int motivo = serverService.bloqueoManiobraLocal(ref, cerrar);
        if (motivo != 0) {
            Toast.makeText(this, motivo, Toast.LENGTH_LONG).show();
            return;
        }
        serverService.maniobraLocal(ref, cerrar);
    }

    /** Tiempos de maniobra comunes a todos los aparatos (5.6.0). */
    private void mostrarTiempos() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setPadding(pad, pad / 2, pad, 0);
        final android.widget.EditText[] campos = new android.widget.EditText[3];
        int[] titulos = {R.string.tiempos_apertura, R.string.tiempos_cierre, R.string.tiempos_falla};
        long[] valores = {EstadoLocal.tiempoAperturaMs, EstadoLocal.tiempoCierreMs, EstadoLocal.tiempoFallaMs};
        for (int i = 0; i < 3; i++) {
            TextView t = new TextView(this);
            t.setText(titulos[i]);
            t.setTextSize(12f);
            caja.addView(t);
            campos[i] = new android.widget.EditText(this);
            campos[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            campos[i].setText(String.valueOf(valores[i]));
            caja.addView(campos[i]);
        }
        new AlertDialog.Builder(this, R.style.AboutDialogTheme)
                .setTitle(R.string.tiempos_titulo)
                .setView(caja)
                .setPositiveButton(R.string.tiempos_guardar, (d, w) -> {
                    EstadoLocal.tiempoAperturaMs = numero(campos[0], EstadoLocal.tiempoAperturaMs);
                    EstadoLocal.tiempoCierreMs = numero(campos[1], EstadoLocal.tiempoCierreMs);
                    EstadoLocal.tiempoFallaMs = numero(campos[2], EstadoLocal.tiempoFallaMs);
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private static long numero(android.widget.EditText e, long siNo) {
        try {
            return Math.max(0, Math.min(60000, Long.parseLong(e.getText().toString().trim())));
        } catch (NumberFormatException ex) {
            return siNo;
        }
    }

    private String textoRespuesta(EstadoLocal.Respuesta r) {
        switch (r) {
            case SIN_CABLEAR: return getString(R.string.respuesta_sin_cablear);
            case TRABADO:     return getString(R.string.respuesta_trabado);
            case LENTO:       return getString(R.string.respuesta_lento,
                    (int) (EstadoLocal.recorridoLentoMs / 1000));
            default:          return getString(R.string.respuesta_normal);
        }
    }

    /** Resalta la opcion activa del par y apaga la otra. */
    private void pintarPar(Button izq, Button der, boolean izqActiva) {
        int on = getResources().getColor(R.color.brand_amber);
        int off = getResources().getColor(R.color.text_secondary);
        izq.setBackgroundTintList(android.content.res.ColorStateList.valueOf(izqActiva ? on : off));
        der.setBackgroundTintList(android.content.res.ColorStateList.valueOf(izqActiva ? off : on));
    }

    /** Texto corto del modelo de control, para que se vea por que un objeto no opera. */
    private String descripcionCtlModel(int ctlModel) {
        switch (ctlModel) {
            case 1:  return "mando directo";
            case 2:  return "SBO normal";
            case 3:  return "directo reforzado";
            case 4:  return "SBO reforzado";
            default: return getString(R.string.maniobra_sin_mando);
        }
    }

    private void showAboutDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AboutDialogTheme);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_about, null);
        // La versión sale del build (build.gradle), con el commit compilado. Hasta la 5.0 era
        // un texto fijo en strings.xml y no coincidía con lo que veía Android (1.0).
        TextView tvVersion = dialogView.findViewById(R.id.tv_about_version);
        tvVersion.setText(getString(R.string.about_version,
                BuildConfig.VERSION_NAME + " (" + BuildConfig.COMMIT + ")"));

        builder.setView(dialogView);
        builder.setPositiveButton(getString(R.string.btn_close), null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void checkPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permisos necesarios no otorgados",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
    }

    private void openFilePicker() {
        // Check if server is running - stop it first
        if (serverServiceBound && serverService != null && serverService.isRunning()) {
            new AlertDialog.Builder(this)
                    .setTitle("Servidor Activo")
                    .setMessage("El servidor esta ejecutandose. Desea detenerlo para seleccionar otro archivo?")
                    .setPositiveButton("Detener y Continuar", (dialog, which) -> {
                        stopServerSafely(() -> openFilePickerIntent());
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }

        openFilePickerIntent();
    }

    private void openFilePickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"application/xml", "text/xml", "*/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        filePickerLauncher.launch(intent);
    }

    /**
     * Load SCL file asynchronously with CACHE support
     * First load: Parse and cache (~20-40s)
     * Subsequent loads: Load from cache (<2s)
     */
    private void loadSclFileAsync(Uri uri) {
        // Show loading indicator
        showLoading(getString(R.string.msg_loading_scl));

        executor.execute(() -> {
            // El usuario esta esperando mirando la pantalla: que no lo degraden
            // a un nucleo de baja potencia.
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_DISPLAY);
            long startTime = System.currentTimeMillis();

            try {
                // Get file name
                String fileName = getFileName(uri);
                String fileIdentifier = uri.toString(); // Use full URI as unique identifier

                mainHandler.post(() -> tvFileName.setText("Archivo: " + fileName));
                archivoEsScd = fileName != null && fileName.toLowerCase().endsWith(".scd");

                // Verify extension
                if (!isValidSclFile(fileName)) {
                    mainHandler.post(() -> {
                        hideLoading();
                        Toast.makeText(this, "Seleccione un archivo SCL valido (.icd, .cid, .scd, .scl)",
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                // Take persistent permission
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                try {
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                } catch (SecurityException e) {
                    Log.w(TAG, "Could not take persistable permission", e);
                }

                mainHandler.post(() -> {
                    mensajeCarga = getString(R.string.msg_parsing_model);
                    avisoScdVisible = archivoEsScd;
                    pintarCarga();
                });

                // FASE 1: listar los IED con un barrido DOM barato, sin tocar
                // SclParser (que es el 93% del costo). Asi el selector aparece casi
                // al instante en vez de despues de parsear todo: medido sobre un SCD
                // de subestacion de 14 MB con 52 IEDs, 616 ms contra varios minutos.
                List<String> iedNames;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    iedNames = SclFileParser.listIedNames(is);
                }

                if (iedNames.isEmpty()) {
                    mainHandler.post(() -> {
                        hideLoading();
                        Toast.makeText(this, getString(R.string.msg_no_models), Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                final List<String> finalIedNames = iedNames;

                if (iedNames.size() == 1) {
                    // Un solo IED: no hay nada que elegir, se parsea directo.
                    cargarIed(uri, fileIdentifier, 0, finalIedNames, startTime);
                } else {
                    // FASE 2: elegir primero, parsear despues — y parsear SOLO el
                    // elegido, en vez de construir el modelo de todos los equipos
                    // para usar uno.
                    mainHandler.post(() -> {
                        hideLoading();
                        mostrarSelectorDeIed(uri, fileIdentifier, finalIedNames);
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Error loading SCL file", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(this, "Error al cargar archivo: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Selector de dispositivo. Se muestra ANTES de parsear: los nombres salen de
     * un barrido DOM barato, asi que aparece casi al instante y el usuario elige
     * sabiendo lo mismo que antes, pero sin haber esperado el parseo de equipos
     * que no va a usar.
     */
    private void mostrarSelectorDeIed(Uri uri, String fileIdentifier, List<String> iedNames) {
        String[] labels = new String[iedNames.size()];
        for (int i = 0; i < iedNames.size(); i++) {
            labels[i] = (i + 1) + ". " + iedNames.get(i);
        }

        new AlertDialog.Builder(this)
                .setTitle("Archivo con " + iedNames.size()
                        + " dispositivos - seleccione el IED a simular")
                .setCancelable(false)
                .setItems(labels, (dialog, which) -> {
                    showLoading(getString(R.string.msg_parsing_model));
                    avisoScdVisible = archivoEsScd;
                    pintarCarga();
                    executor.execute(() -> {
                        android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_DISPLAY);
                        cargarIed(uri, fileIdentifier, which, iedNames,
                                System.currentTimeMillis());
                    });
                })
                .show();
    }

    /**
     * Parsea SOLO el IED elegido y lo deja activo. Corre en el hilo de trabajo.
     */
    private void cargarIed(Uri uri, String fileIdentifier, int iedIndex,
                           List<String> iedNames, long startTime) {
        try {
            List<ServerModel> models;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                models = SclFileParser.parseOnlyIed(is, iedIndex);
            }

            if (models == null || models.isEmpty()) {
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(this, getString(R.string.msg_no_models), Toast.LENGTH_LONG).show();
                });
                return;
            }

            final List<ServerModel> finalModels = models;
            final long elapsed = System.currentTimeMillis() - startTime;

            mainHandler.post(() -> {
                hideLoading();
                // Tras recortar queda un solo modelo, pero se conserva el nombre
                // que el usuario eligio para etiquetarlo bien.
                parsedModels = finalModels;
                parsedIedNames = (iedIndex < iedNames.size())
                        ? new ArrayList<>(java.util.Collections.singletonList(iedNames.get(iedIndex)))
                        : new ArrayList<>();
                selectedIedIndex = iedIndex;
                applySelectedModel(0, fileIdentifier, elapsed);
            });

        } catch (Exception e) {
            Log.e(TAG, "Error al cargar el IED " + iedIndex, e);
            mainHandler.post(() -> {
                hideLoading();
                Toast.makeText(this, "Error al cargar archivo: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    /**
     * Apply the chosen model (by index) as the active one: show it in the tree,
     * cache its structure and remember the index so the server simulates the same IED.
     */
    private void applySelectedModel(int index, String fileIdentifier, long elapsed) {
        selectedModelIndex = index;
        ServerModel model = parsedModels.get(index);
        loadedModel = model;
        displayModelInfo(model);
        btnStartStop.setEnabled(true);

        // NO se cachea la estructura. Medido con el modelo de un relé real: escribir el modelo
        // eran 105.277 filas, 13,3 SEGUNDOS de hilo de fondo compitiendo por CPU y disco
        // justo despues de cargar, y una base de 413 MB (mas 51 MB de WAL) por archivo.
        //
        // Y no servia para nada: isCached()/getCachedStructure() no se llaman desde
        // ningun lado, asi que el cache se escribia y nunca se leia. Ademas solo guarda
        // la estructura para el arbol, no el ServerModel, asi que ni siquiera podria
        // evitar el SclParser, que es el 65% del tiempo de carga.
        //
        // Se deja la clase por si alguna vez se implementa la lectura, pero mientras
        // tanto no se la invoca. Ver el borrado de la base vieja en onCreate().

        String timeMsg = getString(R.string.msg_file_loaded) + " (" + (elapsed / 1000) + "s)";
        if (parsedModels.size() > 1) {
            timeMsg = "Dispositivo " + (index + 1) + " de " + parsedModels.size() + " cargado";
        }
        Toast.makeText(this, timeMsg, Toast.LENGTH_SHORT).show();

        Log.i(TAG, "Model " + index + "/" + parsedModels.size() + " loaded in " + elapsed + "ms");
    }

    /**
     * The file contains several IEDs/AccessPoints - let the user pick which one to simulate
     */
    private void showDeviceSelectionDialog(String fileIdentifier, long elapsed) {
        String[] labels = new String[parsedModels.size()];
        for (int i = 0; i < parsedModels.size(); i++) {
            labels[i] = describeModel(parsedModels.get(i), i);
        }

        new AlertDialog.Builder(this)
                .setTitle("Archivo con " + parsedModels.size() + " dispositivos - seleccione el IED a simular")
                .setCancelable(false)
                .setItems(labels, (dialog, which) ->
                        applySelectedModel(which, fileIdentifier, elapsed))
                .show();
    }

    /**
     * Human-readable label for a model: its logical devices (LD names include the IED name)
     */
    /**
     * Etiqueta de un dispositivo en el dialogo de seleccion.
     *
     * Se prefiere el nombre del IED tal como lo declara el SCL ("B02_R22_F003"),
     * que es lo que muestra IEDScout. Deducirlo del modelo daria el nombre del
     * primer Logical Device ("B02_R22_F003AnUn_MeasTransdIn"), que mezcla el
     * nombre del equipo con el de un LD cualquiera y confunde al elegir.
     */
    private String describeModel(ServerModel model, int index) {
        StringBuilder sb = new StringBuilder();
        int ldCount = model.getChildren().size();

        String name = null;
        if (parsedIedNames != null && index < parsedIedNames.size()) {
            name = parsedIedNames.get(index);
        }
        if (name == null || name.isEmpty()) {
            // sin nombre del SCL: caer al primer LD
            for (com.beanit.iec61850bean.ModelNode ld : model.getChildren()) {
                name = ld.getName();
                break;
            }
        }

        sb.append(index + 1).append(". ");
        sb.append(name != null ? name : "Dispositivo " + (index + 1));
        sb.append("  (").append(ldCount).append(ldCount == 1 ? " LD)" : " LDs)");
        return sb.toString();
    }

    private void showLoading(String message) {
        layoutLoading.setVisibility(View.VISIBLE);
        rvDataModel.setVisibility(View.GONE);
        mensajeCarga = message;
        avisoScdVisible = false;   // la explicacion la pide solo la carga de un SCD
        inicioCarga = System.currentTimeMillis();
        mainHandler.removeCallbacks(relojCarga);
        pintarCarga();
        mainHandler.postDelayed(relojCarga, 1000);
    }

    private void hideLoading() {
        mainHandler.removeCallbacks(relojCarga);
        layoutLoading.setVisibility(View.GONE);
        rvDataModel.setVisibility(View.VISIBLE);
    }

    /** Mensaje + explicacion de SCD (si corresponde) + segundos, a partir de los 2 s. */
    private void pintarCarga() {
        long s = (System.currentTimeMillis() - inicioCarga) / 1000;
        StringBuilder t = new StringBuilder(mensajeCarga);
        if (avisoScdVisible) t.append("\n\n").append(getString(R.string.msg_loading_scd_hint));
        if (s >= 2) t.append("\n").append(getString(R.string.msg_loading_elapsed, s));
        tvLoadingMessage.setText(t);
    }

    private String getFileName(Uri uri) {
        String result = "unknown";
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if ("unknown".equals(result)) {
            result = uri.getLastPathSegment();
        }
        return result;
    }

    private boolean isValidSclFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".icd") || lower.endsWith(".cid") ||
                lower.endsWith(".scd") || lower.endsWith(".scl");
    }

    private void displayModelInfo(ServerModel model) {
        StringBuilder info = new StringBuilder();
        info.append("Logical Devices: ").append(model.getChildren().size()).append("\n");

        int lnCount = 0;
        int doCount = 0;
        for (var ld : model.getChildren()) {
            lnCount += ld.getChildren().size();
            for (var ln : ld.getChildren()) {
                doCount += ln.getChildren().size();
            }
        }
        info.append("Logical Nodes: ").append(lnCount).append("\n");
        info.append("Data Objects: ").append(doCount);

        tvModelInfo.setText(info.toString());
        tvModelInfo.setVisibility(View.VISIBLE);

        // Build tree in background for large models
        executor.execute(() -> {
            long startTime = System.currentTimeMillis();
            List<ModelTreeAdapter.TreeNode> nodes = ModelTreeAdapter.buildTree(model);
            long buildTime = System.currentTimeMillis() - startTime;
            Log.d(TAG, "Tree built: " + nodes.size() + " nodes in " + buildTime + "ms");

            // Pre-build nodeMap in background to avoid main thread work
            java.util.Map<String, ModelTreeAdapter.TreeNode> nodeMap = new java.util.HashMap<>();
            for (ModelTreeAdapter.TreeNode node : nodes) {
                nodeMap.put(node.reference, node);
            }
            long mapTime = System.currentTimeMillis() - startTime - buildTime;
            Log.d(TAG, "NodeMap built: " + nodeMap.size() + " entries in " + mapTime + "ms");

            mainHandler.post(() -> {
                // Nodes are already collapsed by default (expanded = false in constructor)
                // Use updateDataWithMap to avoid rebuilding nodeMap on main thread
                modelAdapter.updateDataWithMap(nodes, nodeMap);
            });
        });
    }

    private void displayIpAddress() {
        java.util.List<String> ips = getLocalIpAddresses();
        int puerto = getSelectedPort();
        if (!ips.isEmpty()) {
            StringBuilder sb = new StringBuilder("IP: ");
            for (int i = 0; i < ips.size(); i++) {
                if (i > 0) sb.append("\n     ");
                sb.append(ips.get(i)).append(":").append(puerto);
            }
            tvIpAddress.setText(sb.toString());
        } else {
            tvIpAddress.setText("IP: No conectado a WiFi");
        }
    }

    /**
     * Todas las direcciones IPv4 en las que el servidor queda accesible.
     *
     * ServerSap se crea sin direccion de enlace, o sea que escucha en 0.0.0.0:
     * responde por CUALQUIER interfaz del dispositivo. Mostrar una sola es
     * enganoso, y con dos WiFi activas -- el equipo unido a una red y a la vez
     * compartiendo la suya como punto de acceso -- cual se mostraba dependia del
     * orden de enumeracion.
     *
     * Antes ademas se descartaba 192.168.43.x "por ser hotspot". Eso estaba mal
     * por dos motivos: el rango de hotspot ya no es fijo (en un POCO X5 es
     * 10.44.194.x), y sobre todo el cliente que se conecta al punto de acceso del
     * propio equipo llega justamente por esa direccion. Se listan todas.
     */
    private java.util.List<String> getLocalIpAddresses() {
        java.util.List<String> ips = new ArrayList<>();
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                 en != null && en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                if (!intf.isUp() || intf.isLoopback()) continue;
                for (Enumeration<InetAddress> dirs = intf.getInetAddresses(); dirs.hasMoreElements();) {
                    InetAddress a = dirs.nextElement();
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip != null && !ips.contains(ip)) ips.add(ip);
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "Error getting IP from NetworkInterface", e);
        }

        if (ips.isEmpty()) {
            // Respaldo: si la enumeracion no dio nada, al menos la de WiFi.
            try {
                WifiManager wifiManager = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                    if (ipInt != 0) {
                        ips.add(String.format("%d.%d.%d.%d",
                                (ipInt & 0xff), (ipInt >> 8 & 0xff),
                                (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff)));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting IP from WifiManager", e);
            }
        }
        return ips;
    }
    private void toggleServer() {
        if (isServerStopping) {
            Toast.makeText(this, "Espere, deteniendo servidor...", Toast.LENGTH_SHORT).show();
            return;
        }

        if (serverServiceBound && serverService != null && serverService.isRunning()) {
            stopServerSafely(null);
        } else {
            startServer();
        }
    }

    private void startServer() {
        if (selectedFileUri == null) {
            Toast.makeText(this, getString(R.string.msg_select_scl_first), Toast.LENGTH_SHORT).show();
            return;
        }

        // Get port from input
        int port = getSelectedPort();
        if (port < 1 || port > 65535) {
            Toast.makeText(this, getString(R.string.msg_invalid_port), Toast.LENGTH_SHORT).show();
            return;
        }
        // Android, como todo Linux, reserva los puertos < 1024 para procesos
        // privilegiados: bind() falla con EACCES y el servidor no arranca. El 102
        // del estandar IEC 61850 cae justo ahi, asi que conviene decirlo antes de
        // intentarlo en vez de dejar que falle sin explicacion.
        if (port < 1024) {
            Toast.makeText(this, getString(R.string.msg_port_privilegiado, port),
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Entregarle al servicio el modelo que ya parseamos para mostrar el arbol:
        // sin esto vuelve a parsear el mismo archivo desde cero y el usuario
        // espera dos veces lo mismo (medido: ~11 s en la Activity + ~7 s aca,
        // sobre un MiCOM de 879 KB).
        if (parsedModels != null && selectedModelIndex < parsedModels.size()) {
            IEDServerService.ofrecerModeloPreparseado(
                    selectedFileUri, parsedModels.get(selectedModelIndex));
        }

        Intent intent = new Intent(this, IEDServerService.class);
        intent.setAction(IEDServerService.ACTION_START);
        intent.setData(selectedFileUri);
        intent.putExtra(IEDServerService.EXTRA_PORT, port);
        // Va el indice DENTRO DEL ARCHIVO, no el de parsedModels: es el que le
        // sirve al servicio para recortar al IED correcto si tiene que parsear.
        intent.putExtra(IEDServerService.EXTRA_MODEL_INDEX, selectedIedIndex);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        // Bind to get updates
        bindService(intent, serverServiceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Stop server safely with proper cleanup
     */
    private void stopServerSafely(Runnable onComplete) {
        if (isServerStopping) {
            return;
        }

        isServerStopping = true;
        btnStartStop.setEnabled(false);

        // First unbind
        if (serverServiceBound) {
            try {
                unbindService(serverServiceConnection);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service", e);
            }
            serverServiceBound = false;
        }

        // Then stop
        Intent intent = new Intent(this, IEDServerService.class);
        intent.setAction(IEDServerService.ACTION_STOP);
        startService(intent);

        // Update UI after a short delay to allow service to stop
        mainHandler.postDelayed(() -> {
            serverService = null;
            isServerStopping = false;
            btnStartStop.setEnabled(selectedFileUri != null);
            updateServerStatus(false, null);

            if (onComplete != null) {
                onComplete.run();
            }
        }, 500);
    }

    private int getSelectedPort() {
        try {
            String portStr = etPort.getText().toString().trim();
            return Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            // El mismo puerto por defecto que el campo y el servicio. Antes devolvía 102, que
            // startServer() rechaza enseguida por ser menor a 1024: con el campo vacío el
            // servidor no arrancaba y el aviso hablaba de un puerto que el usuario no puso.
            return 10102;
        }
    }

    private void updateServerStatus(boolean running, String message) {
        runOnUiThread(() -> {
            if (running) {
                tvStatus.setText("Estado: EJECUTANDO");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running));
                viewStatusIndicator.setBackgroundTintList(
                        ContextCompat.getColorStateList(this, R.color.status_running));
                btnStartStop.setText(getString(R.string.btn_stop_server));
            } else {
                tvStatus.setText("Estado: DETENIDO" + (message != null ? " - " + message : ""));
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped));
                viewStatusIndicator.setBackgroundTintList(
                        ContextCompat.getColorStateList(this, R.color.status_stopped));
                btnStartStop.setText(getString(R.string.btn_start_server));
            }
            displayIpAddress();
        });
    }

    private void onDataChanged(String nodeRef, String value) {
        runOnUiThread(() -> {
            // Ensure we're using the server's model instance (first change syncs models)
            if (serverServiceBound && serverService != null) {
                ServerModel serverModel = serverService.getServerModel();
                if (serverModel != null && serverModel != loadedModel) {
                    // The server has its own model instance - sync once
                    loadedModel = serverModel;
                    modelAdapter.refreshValuesFromModel(serverModel);
                    Log.d(TAG, "Server model synced with UI");
                }
            }

            // Update the tree adapter with the new value
            modelAdapter.updateNodeValue(nodeRef, value);
        });
    }

    private void onUserChangedValue(String nodeRef, String newValue) {
        if (serverServiceBound && serverService != null) {
            serverService.setDataValue(nodeRef, newValue);
        }
    }

    private String extractFcFromRef(String nodeRef) {
        if (nodeRef.contains("$")) {
            String[] parts = nodeRef.split("\\$");
            if (parts.length >= 2) {
                String lastPart = parts[parts.length - 1];
                if (isValidFc(lastPart)) {
                    return lastPart;
                }
                if (isValidFc(parts[1])) {
                    return parts[1];
                }
            }
        }
        return "ST";
    }

    private boolean isValidFc(String fc) {
        return "ST".equals(fc) || "MX".equals(fc) || "CO".equals(fc) || "SP".equals(fc) ||
                "SG".equals(fc) || "SE".equals(fc) || "SV".equals(fc) || "CF".equals(fc) ||
                "DC".equals(fc) || "EX".equals(fc) || "SR".equals(fc) || "OR".equals(fc) ||
                "BL".equals(fc) || "RP".equals(fc) || "LG".equals(fc) || "BR".equals(fc) ||
                "GO".equals(fc) || "GS".equals(fc) || "MS".equals(fc) || "US".equals(fc);
    }

    private String cleanReference(String nodeRef) {
        if (nodeRef.contains("$")) {
            String[] parts = nodeRef.split("\\$");

            String lastPart = parts[parts.length - 1];
            if (isValidFc(lastPart)) {
                return parts[0];
            }

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

    private void updateUI() {
        if (serverServiceBound && serverService != null) {
            updateServerStatus(serverService.isRunning(), null);

            ServerModel model = serverService.getServerModel();
            // Solo si es OTRO modelo. Al enlazar el servicio despues de arrancarlo, el
            // modelo es el mismo que la Activity ya mostro —se lo paso ella—, y volver a
            // llamar a displayModelInfo() reconstruia el arbol entero por gusto: medido,
            // 92.527 nodos y 580 ms mas de espera cada vez que se inicia el servidor.
            if (model != null && model != loadedModel) {
                loadedModel = model;
                displayModelInfo(model);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Register server broadcast receiver
        IntentFilter serverFilter = new IntentFilter();
        serverFilter.addAction(IEDServerService.ACTION_SERVER_STATUS);
        serverFilter.addAction(IEDServerService.ACTION_DATA_CHANGED);
        serverFilter.addAction(IEDServerService.ACTION_CLIENT_CONNECTED);
        LocalBroadcastManager.getInstance(this).registerReceiver(serverUpdateReceiver, serverFilter);

        // Try to bind if server service is running
        Intent serverIntent = new Intent(this, IEDServerService.class);
        bindService(serverIntent, serverServiceConnection, 0);
    }

    @Override
    protected void onStop() {
        super.onStop();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(serverUpdateReceiver);

        if (serverServiceBound) {
            try {
                unbindService(serverServiceConnection);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding server service", e);
            }
            serverServiceBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Primero las limpiezas de las ventanas abiertas y despues el executor: al
        // reves quedaria una ventana viva contra un executor ya cerrado, que es
        // justamente el cuadro que tumbaba el proceso.
        for (Runnable limpiar : new java.util.ArrayList<>(limpiezas.values())) {
            try { limpiar.run(); } catch (Exception ignore) { }
        }
        limpiezas.clear();
        executor.shutdown();
    }
}

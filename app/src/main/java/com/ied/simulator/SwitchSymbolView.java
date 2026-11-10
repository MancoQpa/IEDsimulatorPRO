package com.ied.simulator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

/**
 * Simbolo unifilar del aparato, para acompañar al texto de estado.
 *
 * El texto sigue estando: el simbolo no lo reemplaza. La razon es que el color
 * por si solo es ambiguo -- verde/rojo se lee como "energizado/desenergizado" o
 * como "cerrado/abierto" segun a quien se le pregunte -- y ademas aplastaba
 * INTERMEDIO e INVALIDO en un mismo ambar, cuando son cosas distintas:
 * "no se donde esta" contra "los contactos se contradicen". Esos dos son
 * justamente los estados que se ven en un equipo de banco sin contactos
 * auxiliares cableados, y los que importan antes de mandar.
 *
 * La forma la decide el estado; el color se mantiene igual al que ya usaba la
 * lista, para no cambiar dos cosas a la vez.
 */
public class SwitchSymbolView extends View {

    /** Los cuatro estados del doble punto, tal como los nombra EstadoLocal.Objeto. */
    public static final String ABIERTO    = "ABIERTO";
    public static final String CERRADO    = "CERRADO";
    public static final String INTERMEDIO = "INTERMEDIO";
    public static final String INVALIDO   = "INVALIDO";

    private String estado = INTERMEDIO;
    private boolean interruptor = false;   // true = XCBR (cuadro), false = cuchilla
    private boolean admiteMando = true;

    private final Paint trazo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint relleno = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint aspa = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SwitchSymbolView(Context c) { this(c, null); }

    public SwitchSymbolView(Context c, AttributeSet a) {
        super(c, a);
        trazo.setStyle(Paint.Style.STROKE);
        trazo.setStrokeCap(Paint.Cap.ROUND);
        relleno.setStyle(Paint.Style.FILL);
        aspa.setStyle(Paint.Style.STROKE);
        aspa.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * @param estado      uno de ABIERTO/CERRADO/INTERMEDIO/INVALIDO
     * @param nombre      "XCBR1.Pos", "CSWI1.Pos"... de ahi sale la forma
     * @param admiteMando false si el objeto solo publica posicion (ctlModel = 0)
     */
    public void setObjeto(String estado, String nombre, boolean admiteMando) {
        this.estado = estado == null ? INTERMEDIO : estado;
        // La clase del nodo logico decide la forma. Si el punto viene del CSWI
        // no se sabe que aparato hay detras -- el modelo no lo dice -- y se
        // dibuja la cuchilla, que es el simbolo generico de maniobra.
        this.interruptor = nombre != null && nombre.contains("XCBR");
        this.admiteMando = admiteMando;
        invalidate();
    }

    private int color() {
        if (CERRADO.equals(estado))    return ContextCompat.getColor(getContext(), R.color.status_running);
        if (ABIERTO.equals(estado))    return ContextCompat.getColor(getContext(), R.color.status_stopped);
        if (INVALIDO.equals(estado))   return ContextCompat.getColor(getContext(), R.color.state_bad);
        return ContextCompat.getColor(getContext(), R.color.brand_amber);   // INTERMEDIO
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int lado = (int) (36 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(lado, wSpec), resolveSize(lado, hSpec));
    }

    @Override
    protected void onDraw(Canvas cv) {
        final float w = getWidth(), h = getHeight();
        final float s = Math.min(w, h);
        final float cx = w / 2f, cy = h / 2f;
        final float grosor = Math.max(2f, s * 0.075f);

        final int c = color();
        trazo.setColor(c);
        trazo.setStrokeWidth(grosor);
        relleno.setColor(c);

        // Los bornes fijos, arriba y abajo. Se dibujan SIEMPRE enteros: probado en
        // telefono, a 36 dp el punteado se rompe en dos puntitos y el simbolo se
        // lee como un signo de admiracion. Lo que solo se observa se marca con
        // trazo mas fino y translucido, que a ese tamaño si se distingue.
        trazo.setStrokeWidth(admiteMando ? grosor : grosor * 0.6f);
        trazo.setAlpha(admiteMando ? 255 : 130);
        relleno.setAlpha(admiteMando ? 255 : 130);
        final float yTop = cy - s * 0.40f, yBot = cy + s * 0.40f;
        final float yCon1 = cy - s * 0.22f, yCon2 = cy + s * 0.22f;
        cv.drawLine(cx, yTop, cx, yCon1, trazo);
        cv.drawLine(cx, yCon2, cx, yBot, trazo);
        trazo.setStrokeWidth(grosor);

        if (interruptor) dibujarInterruptor(cv, cx, cy, s, grosor);
        else             dibujarCuchilla(cv, cx, yCon1, yCon2, s, grosor);

        if (INVALIDO.equals(estado)) {
            // Aspa: contactos que se contradicen. No es "no se", es "esto esta mal".
            aspa.setColor(c);
            aspa.setStrokeWidth(grosor * 0.9f);
            float r = s * 0.20f;
            cv.drawLine(cx - r, cy - r, cx + r, cy + r, aspa);
            cv.drawLine(cx + r, cy - r, cx - r, cy + r, aspa);
        }
    }

    /** XCBR: cuadro. Relleno = cerrado, hueco = abierto, punteado = indefinido. */
    private void dibujarInterruptor(Canvas cv, float cx, float cy, float s, float grosor) {
        float r = s * 0.19f;
        if (CERRADO.equals(estado)) {
            cv.drawRect(cx - r, cy - r, cx + r, cy + r, relleno);
        } else {
            if (INTERMEDIO.equals(estado)) {
                trazo.setPathEffect(new DashPathEffect(new float[]{grosor, grosor}, 0));
            }
            cv.drawRect(cx - r, cy - r, cx + r, cy + r, trazo);
            trazo.setPathEffect(null);
        }
    }

    /** XSWI y generico: cuchilla. El angulo dice la posicion. */
    private void dibujarCuchilla(Canvas cv, float cx, float yCon1, float yCon2,
                                 float s, float grosor) {
        // Punto fijo abajo; la cuchilla gira desde ahi.
        float largo = yCon2 - yCon1;
        double ang;                       // 0 = vertical (cerrada)
        if (CERRADO.equals(estado))         ang = 0;
        else if (ABIERTO.equals(estado))    ang = Math.toRadians(38);
        else                                ang = Math.toRadians(19);  // a medio camino

        float xPunta = cx + (float) (Math.sin(ang) * largo);
        float yPunta = yCon2 - (float) (Math.cos(ang) * largo);

        if (INTERMEDIO.equals(estado)) {
            trazo.setPathEffect(new DashPathEffect(new float[]{grosor, grosor * 0.9f}, 0));
        }
        cv.drawLine(cx, yCon2, xPunta, yPunta, trazo);
        trazo.setPathEffect(null);

        // El borne de arriba se marca siempre, este o no tocado por la cuchilla:
        // asi se ve que hay un contacto y que la cuchilla no llega.
        relleno.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx, yCon1, grosor * 0.75f, relleno);
    }
}

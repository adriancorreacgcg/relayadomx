package com.relayadomx;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

public class MainActivity extends Activity implements Pn532.Log, Lector.Aviso, Remoto.Enlace, Remoto.Log {

    public static volatile MainActivity I;

    private static final String USB_PERMISSION_ACTION = "com.relayadomx.USB_PERMISSION";
    private static final int NINGUNO = 0;
    private static final int PAGAR = 1;
    private static final int TARJETA = 2;

    private UsbManager usbManager;
    private TextView modoEstado;
    private TextView brokerEstado;
    private TextView enlaceEstado;
    private TextView tarjetaEstado;
    private TextView tarjetaDatos;
    private TextView txEstado;
    private TextView cardInfo;
    private TextView logText;
    private ScrollView scrollLog;
    private Button aprobadoButton;
    private EditText panText;
    private EditText vencText;
    private EditText servicioText;
    private EditText claveText;
    private final StringBuilder logBuffer = new StringBuilder();
    private final StringBuilder logPendiente = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean flushAgendado;
    private final List<byte[]> historial = new ArrayList<>();
    private final ExecutorService trabajo = Executors.newSingleThreadExecutor();
    private Pn532 pn532;
    private Lector lector;
    private volatile boolean relay;
    private volatile boolean estatico;
    private volatile boolean aprender;
    private volatile boolean arrancando;
    private volatile int genDren;
    private volatile int modo = NINGUNO;
    private volatile int servidor = Remoto.APAGADO;
    private volatile String servidorTexto = "apagado";
    private volatile boolean enlazado;
    private volatile boolean tarjetaLista;
    private volatile String datosTarjeta = "";
    private volatile boolean tx;
    private volatile long txUltima;
    private Thread hiloRelay;
    private UsbDevice pendiente;
    private volatile int ultimoEstado = -1;

    private final Runnable latidoTx = new Runnable() {
        @Override public void run() {
            if (tx && System.currentTimeMillis() - txUltima > 5000) {
                tx = false;
            }
            if (modo != NINGUNO) render();
            ui.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!USB_PERMISSION_ACTION.equals(intent.getAction())) return;
            if (pendiente != null && usbManager.hasPermission(pendiente)) {
                abrir(pendiente);
            } else {
                log("Permiso USB denegado");
            }
            pendiente = null;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        I = this;
        Remoto.registro = this;
        Aprendiz.iniciar(this);
        Datos.iniciar(this);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        modoEstado = findViewById(R.id.modoEstado);
        brokerEstado = findViewById(R.id.brokerEstado);
        enlaceEstado = findViewById(R.id.enlaceEstado);
        tarjetaEstado = findViewById(R.id.tarjetaEstado);
        tarjetaDatos = findViewById(R.id.tarjetaDatos);
        txEstado = findViewById(R.id.txEstado);
        cardInfo = findViewById(R.id.cardInfo);
        logText = findViewById(R.id.logText);
        scrollLog = findViewById(R.id.scrollLog);
        aprobadoButton = findViewById(R.id.aprobadoButton);
        panText = findViewById(R.id.panText);
        vencText = findViewById(R.id.vencText);
        servicioText = findViewById(R.id.servicioText);
        claveText = findViewById(R.id.claveText);
        panText.setText(Datos.pan);
        vencText.setText(Datos.venc);
        servicioText.setText(Datos.servicio);
        pn532 = new Pn532();
        lector = new Lector(this, this);

        findViewById(R.id.modoPagarButton).setOnClickListener(v -> tocarModo(PAGAR));
        findViewById(R.id.modoTarjetaButton).setOnClickListener(v -> tocarModo(TARJETA));
        findViewById(R.id.apduButton).setOnClickListener(v ->
                scrollLog.setVisibility(scrollLog.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));
        aprobadoButton.setOnClickListener(v -> Remoto.I.aprobar());
        findViewById(R.id.connectButton).setOnClickListener(v -> {
            if (pn532.abierto()) { detenerTodo(); pn532.cerrar(); log("Sin conexión USB"); }
            else conectar();
        });
        findViewById(R.id.aprenderButton).setOnClickListener(v -> iniciarAprender());
        findViewById(R.id.subirAtcButton).setOnClickListener(v -> subirAtc(1000));
        findViewById(R.id.relayButton).setOnClickListener(v -> iniciarRelay());
        findViewById(R.id.estaticoButton).setOnClickListener(v -> iniciarEstatico(1));
        findViewById(R.id.estatico2Button).setOnClickListener(v -> iniciarEstatico(2));
        findViewById(R.id.estatico3Button).setOnClickListener(v -> iniciarEstatico(3));
        findViewById(R.id.estatico4Button).setOnClickListener(v -> iniciarEstatico(4));
        findViewById(R.id.estatico5Button).setOnClickListener(v -> iniciarEstatico(5));
        findViewById(R.id.stopButton).setOnClickListener(v -> detenerTodo());
        findViewById(R.id.firmwareButton).setOnClickListener(v -> capturar());
        findViewById(R.id.volcarButton).setOnClickListener(v -> {
            String t = Aprendiz.volcar();
            log(t.isEmpty() ? "nada guardado" : t);
        });
        findViewById(R.id.clearButton).setOnClickListener(v -> {
            logBuffer.setLength(0);
            logPendiente.setLength(0);
            logText.setText("");
        });

        IntentFilter filter = new IntentFilter(USB_PERMISSION_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, filter);
        }
        render();
        ui.postDelayed(latidoTx, 1000);
        log("Elige modo y usa la misma clave en los dos teléfonos.");
    }

    private void render() {
        runOnUiThread(() -> {
            modoEstado.setText(modo == PAGAR ? "Modo: PAGAR"
                    : modo == TARJETA ? "Modo: TARJETA" : "Modo: ninguno");
            modoEstado.setTextColor(modo == NINGUNO ? 0xFF8FB8DE : 0xFF4C9AFF);

            brokerEstado.setText("Server: " + servidorTexto);
            brokerEstado.setTextColor(servidor == Remoto.CONECTADO ? 0xFF2ECC71
                    : servidor == Remoto.SIN_SERVIDOR ? 0xFFFF5C5C : 0xFFFFB020);

            boolean par = enlazado && Remoto.I.parFresco();
            enlaceEstado.setText(modo == NINGUNO ? "Enlace:"
                    : par ? "Enlace: LISTO" : "Enlace: esperando");
            enlaceEstado.setTextColor(par ? 0xFF2ECC71 : 0xFFFFB020);

            boolean remota = Remoto.I.tarjetaRemotaFresca();
            boolean local = lector.lista();
            String t;
            boolean verde;
            if (modo == TARJETA) {
                t = local ? "Tarjeta: LISTA" : "Tarjeta: sin tarjeta";
                verde = local;
            } else if (modo == PAGAR) {
                t = remota ? "Tarjeta: LISTA EN EL OTRO" : "Tarjeta: sin confirmar";
                verde = remota;
            } else {
                t = "Tarjeta:";
                verde = false;
            }
            tarjetaEstado.setText(t);
            tarjetaEstado.setTextColor(verde ? 0xFF2ECC71 : 0xFF8FB8DE);
            tarjetaDatos.setText(datosTarjeta);

            txEstado.setText(tx ? "Transacción en curso" : "");
            txEstado.setTextColor(0xFFFF5C5C);

            boolean ok = Remoto.I.aprobadoFresco();
            aprobadoButton.setText(ok ? "APROBADO" : "Aprobado");
            aprobadoButton.setTextColor(ok ? 0xFFFFFFFF : 0xFF8FB8DE);
            aprobadoButton.setBackgroundTintList(android.content.res.ColorStateList
                    .valueOf(ok ? 0xFF2ECC71 : 0xFF13293D));
        });
    }

    void apduEntrante() {
        txUltima = System.currentTimeMillis();
        if (!tx) {
            tx = true;
            Remoto.I.limpiarAprobado();
            render();
        }
    }

    private String clave() {
        String c = claveText.getText().toString().trim().toLowerCase();
        return c.isEmpty() ? "0000" : c;
    }

    private void tocarModo(int cual) {
        if (arrancando) return;
        if (modo == cual) { detenerTodo(); return; }
        arrancar(cual);
    }

    private void arrancar(final int cual) {
        arrancando = true;
        final String clave = clave();
        trabajo.execute(() -> {
            try {
                pararInterno();
                modo = cual;
                enlazado = false;
                tarjetaLista = false;
                datosTarjeta = "";
                tx = false;
                render();
                Puente.I.activar();
                preferir(true);
                if (cual == TARJETA) {
                    runOnUiThread(() -> lector.iniciar());
                    log("Modo tarjeta con clave " + clave + ", pon la tarjeta atrás");
                } else {
                    log("Modo pagar con clave " + clave + ", esperando al otro teléfono");
                }
                drenar();
                Remoto.I.conectar(clave, cual == PAGAR, cual == TARJETA ? lector : null, this);
            } catch (Exception e) {
                log("Error al arrancar: " + e.getMessage());
            } finally {
                arrancando = false;
            }
        });
    }

    private void drenar() {
        final int g = ++genDren;
        new Thread(() -> {
            while (g == genDren) {
                String s = Puente.I.sacarBitacora();
                if (!s.isEmpty()) log(s.trim());
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "drenar").start();
    }

    private void detenerTodo() {
        if (arrancando) return;
        arrancando = true;
        trabajo.execute(() -> {
            try {
                pararInterno();
            } finally {
                arrancando = false;
            }
        });
    }

    private void pararInterno() {
        relay = false;
        estatico = false;
        genDren++;
        Puente.I.desactivar();
        Remoto.I.desconectar();
        try { lector.detener(); } catch (Exception ignored) { }
        hiloRelay = null;
        preferir(false);
        modo = NINGUNO;
        enlazado = false;
        tarjetaLista = false;
        datosTarjeta = "";
        tx = false;
        servidor = Remoto.APAGADO;
        servidorTexto = "apagado";
        render();
    }

    @Override public void onServidor(int estado, String texto) {
        servidor = estado;
        servidorTexto = texto;
        render();
    }

    @Override public void onPar(boolean v) {
        enlazado = v;
        render();
    }

    @Override public void onTarjetaRemota(boolean v) {
        tarjetaLista = v;
        render();
    }

    @Override public void onTarjetaDatos(String marca, String pan, String venc) {
        StringBuilder sb = new StringBuilder();
        sb.append(marca);
        if (pan != null && pan.length() >= 4) {
            sb.append("  ···· ").append(pan.substring(pan.length() - 4));
        }
        if (venc != null && !venc.isEmpty()) sb.append("   vence ").append(venc);
        datosTarjeta = sb.toString();
        render();
    }

    @Override public void onAprobado() {
        render();
    }

    @Override public void onAviso(String texto) {
        if ("tarjeta lista".equals(texto)) {
            tarjetaLista = true;
            Remoto.I.tarjetaLocal(true);
            render();
        } else if ("tarjeta fuera".equals(texto)) {
            tarjetaLista = false;
            Remoto.I.tarjetaLocal(false);
            render();
        }
        log(texto);
    }
package com.relayadomx;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
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
    private TextView txEstado;
    private TextView cardInfo;
    private TextView logText;
    private ScrollView scrollLog;
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
    private volatile String broker = "apagado";
    private volatile boolean enlazado;
    private volatile boolean tarjetaLista;
    private volatile boolean tx;
    private volatile long txUltima;
    private Thread hiloRelay;
    private UsbDevice pendiente;
    private volatile int ultimoEstado = -1;

    private final Runnable latidoTx = new Runnable() {
        @Override public void run() {
            if (tx && System.currentTimeMillis() - txUltima > 5000) {
                tx = false;
                render();
            }
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
        txEstado = findViewById(R.id.txEstado);
        cardInfo = findViewById(R.id.cardInfo);
        logText = findViewById(R.id.logText);
        scrollLog = findViewById(R.id.scrollLog);
        panText = findViewById(R.id.panText);
        vencText = findViewById(R.id.vencText);
        servicioText = findViewById(R.id.servicioText);
        claveText = findViewById(R.id.claveText);
        panText.setText(Datos.pan);
        vencText.setText(Datos.venc);
        servicioText.setText(Datos.servicio);
        pn532 = new Pn532();
        lector = new Lector(this, this);

        findViewById(R.id.connectButton).setOnClickListener(v -> {
            if (pn532.abierto()) { detenerTodo(); pn532.cerrar(); log("Sin conexión USB"); }
            else conectar();
        });
        findViewById(R.id.modoPagarButton).setOnClickListener(v -> tocarModo(PAGAR));
        findViewById(R.id.modoTarjetaButton).setOnClickListener(v -> tocarModo(TARJETA));
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
            modoEstado.setText(modo == PAGAR ? "Modo: PAGAR seleccionado"
                    : modo == TARJETA ? "Modo: TARJETA seleccionado"
                    : "Modo: ninguno");
            brokerEstado.setText("Broker: " + broker);
            brokerEstado.setTextColor(broker.startsWith("conectado") ? 0xFF2E7D32
                    : broker.startsWith("sin") ? 0xFFC62828 : 0xFF6D4C41);
            enlaceEstado.setText(modo == NINGUNO ? "Enlace: —"
                    : enlazado ? "Enlace: ENLAZADO al otro teléfono"
                    : "Enlace: esperando al otro teléfono");
            enlaceEstado.setTextColor(enlazado ? 0xFF2E7D32 : 0xFF6D4C41);
            String t;
            if (modo == TARJETA) t = "Tarjeta: " + (tarjetaLista ? "lista" : "sin tarjeta");
            else if (modo == PAGAR) t = "Tarjeta: " + (enlazado
                    ? (tarjetaLista ? "lista en el otro teléfono" : "sin tarjeta en el otro teléfono")
                    : "—");
            else t = "Tarjeta: —";
            tarjetaEstado.setText(t);
            tarjetaEstado.setTextColor(tarjetaLista ? 0xFF2E7D32 : 0xFF6D4C41);
            txEstado.setText(tx ? "Transacción: EN CURSO" : "Transacción: esperando");
            txEstado.setTextColor(tx ? 0xFFC62828 : 0xFF6D4C41);
        });
    }

    void apduEntrante() {
        txUltima = System.currentTimeMillis();
        if (!tx) {
            tx = true;
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
                broker = "conectando…";
                enlazado = false;
                tarjetaLista = false;
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
        tx = false;
        broker = "apagado";
        render();
    }

    @Override public void onBroker(String estado) {
        broker = estado;
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

    @Override public void onAviso(String texto) {
        log(texto);
    }

    @Override public void onLog(String texto) {
        log(texto);
    }

    private void conectar() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) { log("No se encontró adaptador"); return; }
        UsbDevice elegido = drivers.get(0).getDevice();
        for (UsbSerialDriver d : drivers) {
            if (d.getDevice().getVendorId() == 0x10c4) { elegido = d.getDevice(); break; }
        }
        if (!usbManager.hasPermission(elegido)) {
            pendiente = elegido;
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(USB_PERMISSION_ACTION).setPackage(getPackageName()), flags);
            usbManager.requestPermission(elegido, pi);
            log("Esperando permiso USB…");
            return;
        }
        abrir(elegido);
    }

    private void abrir(UsbDevice dispositivo) {
        try {
            pn532.abrir(usbManager, dispositivo, this);
            log("USB conectado · 115200");
        } catch (Exception e) {
            log("Error al conectar: " + e.getMessage());
        }
    }

    private void subirAtc(int veces) {
        if (!pn532.abierto()) { log("Conecta el USB primero"); return; }
        if (relay) return;
        byte[] cmd = Aprendiz.comandoAE();
        if (cmd == null) { log("Aprende primero"); return; }
        relay = true;
        new Thread(() -> {
            int ok = 0;
            try {
                limpiarChip();
                if (!activarTarjeta()) { log("sin tarjeta en el lector"); return; }
                for (int i = 0; i < veces && relay; i++) {
                    byte[] r = enviarCon(cmd, 1, 3000);
                    if (r == null) {
                        if (!recuperar()) { log("tarjeta perdida en " + i); break; }
                        continue;
                    }
                    ok++;
                    if (ok % 100 == 0) log("ATC ahora " + atcDe(r) + " · " + ok + " incrementos");
                }
                log("Listo: " + ok + " incrementos");
            } catch (Exception e) {
                log("Error: " + e.getMessage());
            } finally {
                relay = false;
            }
        }).start();
    }

    private String atcDe(byte[] r) {
        for (int i = 0; i + 3 < r.length; i++) {
            if ((r[i] & 0xff) == 0x9F && (r[i + 1] & 0xff) == 0x36 && (r[i + 2] & 0xff) == 0x02) {
                return String.format("%02X%02X", r[i + 3] & 0xff, r[i + 4] & 0xff);
            }
        }
        return "?";
    }

    private void iniciarRelay() {
        if (!pn532.abierto()) { log("Conecta el USB primero"); return; }
        if (relay) return;
        estatico = false;
        aprender = false;
        Puente.I.activar();
        historial.clear();
        relay = true;
        hiloRelay = new Thread(this::bucleRelay);
        hiloRelay.start();
        preferir(true);
        log("Relay activo: tarjeta en el lector, teléfono en la terminal.");
    }

    private void iniciarAprender() {
        if (!pn532.abierto()) { log("Conecta el USB primero"); return; }
        if (relay) return;
        estatico = false;
        aprender = true;
        Aprendiz.limpiar();
        Puente.I.activar();
        historial.clear();
        relay = true;
        hiloRelay = new Thread(this::bucleRelay);
        hiloRelay.start();
        preferir(true);
        log("Aprendiendo: tarjeta en el lector, presenta el teléfono.");
    }

    private void iniciarEstatico(int variante) {
        detenerTodo();
        aprender = false;
        Datos.pan = panText.getText().toString();
        Datos.venc = vencText.getText().toString();
        Datos.servicio = servicioText.getText().toString();
        Perfil.variante(variante);
        Puente.I.activarEstatico();
        preferir(true);
        log("== modo " + variante + " ==");
        estatico = true;
        new Thread(() -> {
            while (estatico) {
                String s = Puente.I.sacarBitacora();
                if (!s.isEmpty()) log(s.trim());
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }).start();
    }

    private void preferir(boolean activo) {
        NfcAdapter adaptador = NfcAdapter.getDefaultAdapter(this);
        if (adaptador == null) return;
        CardEmulation ce = CardEmulation.getInstance(adaptador);
        try {
            if (activo) {
                ce.setPreferredService(this, new ComponentName(this, HceService.class));
            } else {
                ce.unsetPreferredService(this);
            }
        } catch (Exception e) {
            log("Aviso preferred service: " + e.getMessage());
        }
    }

    private void limpiarChip() {
        pn532.transceive(Pn532Protocol.commandRaw(0x52, new byte[] { 1 }), 300);
        pn532.transceive(Pn532Protocol.commandRaw(0x14, new byte[] { 1, 0x14, 1 }), 600);
    }

    private boolean activarTarjeta() {
        byte[] p = pn532.transceive(Pn532Protocol.commandRaw(0x4A, new byte[] { 1, 0 }), 3000);
        if (p == null) { log("  poll sin respuesta"); return false; }
        return p.length > 2 && (p[1] & 0xff) == 0x4b && (p[2] & 0xff) >= 1;
    }

    private byte[] enviarCon(byte[] apdu, int tg, int ms) {
        ultimoEstado = -1;
        byte[] params = Pn532Protocol.concat(new byte[] { (byte) tg }, apdu);
        byte[] r = pn532.transceive(Pn532Protocol.commandRaw(0x40, params), ms);
        if (r == null) { ultimoEstado = -2; return null; }
        if (r.length < 3 || (r[1] & 0xff) != 0x41) { ultimoEstado = -3; return null; }
        ultimoEstado = r[2] & 0xff;
        if (ultimoEstado != 0) return null;
        byte[] datos = Arrays.copyOfRange(r, 3, r.length);
        return datos.length == 0 ? null : datos;
    }

    private byte[] enviar(byte[] apdu, int tg) {
        return enviarCon(apdu, tg, 1500);
    }

    private byte[] leerRegistroCon(byte[] apdu, int tg, int le, String etiqueta) {
        byte[] mod = apdu.clone();
        mod[4] = (byte) le;
        ByteArrayOutputStream acum = new ByteArrayOutputStream();
        byte[] r = enviarCon(mod, tg, 6000);
        if (r == null) {
            log("  trozo " + etiqueta + " sin respuesta (estado " + ultimoEstado + ")");
            return null;
        }
        for (int vuelta = 0; vuelta < 8; vuelta++) {
            if (r.length < 2) return null;
            int sw1 = r[r.length - 2] & 0xff;
            int sw2 = r[r.length - 1] & 0xff;
            if (sw1 == 0x90 && sw2 == 0x00) {
                acum.write(r, 0, r.length - 2);
                byte[] cuerpo = acum.toByteArray();
                byte[] fin = Arrays.copyOf(cuerpo, cuerpo.length + 2);
                fin[cuerpo.length] = (byte) 0x90;
                fin[cuerpo.length + 1] = 0x00;
                return fin;
            }
            if (sw1 == 0x61) {
                acum.write(r, 0, r.length - 2);
                byte[] gr = new byte[] { 0x00, (byte) 0xC0, 0x00, 0x00, (byte) sw2 };
                r = enviarCon(gr, tg, 3000);
                if (r == null) return null;
                continue;
            }
            if (sw1 == 0x6C) {
                mod[4] = (byte) sw2;
                r = enviarCon(mod, tg, 3000);
                if (r == null) return null;
                continue;
            }
            return r;
        }
        return null;
    }

    private byte[] leerRegistro(byte[] apdu, int tg) {
        if (apdu.length < 5) return null;
        byte[] r = leerRegistroCon(apdu, tg, 0x00, "completo");
        if (r == null) r = leerRegistroCon(apdu, tg, 0x40, "64");
        return r;
    }

    private byte[] intercambiar(byte[] apdu, int tg) {
        if ((apdu[1] & 0xff) == 0xB2) return leerRegistro(apdu, tg);
        return enviar(apdu, tg);
    }

    private boolean recuperar() {
        pn532.transceive(Pn532Protocol.commandRaw(0x14, new byte[] { 1, 0x14, 1 }), 600);
        if (!activarTarjeta()) { log("No pude reactivar la tarjeta"); return false; }
        if (historial.isEmpty()) return true;
        log("Reactivo y repongo " + historial.size() + " comando(s) de contexto");
        for (byte[] h : historial) {
            byte[] resp = enviar(h, 1);
            log("  repongo " + Pn532Protocol.hex(h) + (resp == null ? " falló" : " ok"));
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
        }
        return true;
    }

    private void bucleRelay() {
        int tg = 0;
        int fallos = 0;
        log("Limpiando estado del chip");
        limpiarChip();
        while (relay) {
            try {
                if (tg == 0) {
                    if (activarTarjeta()) {
                        tg = 1;
                        fallos = 0;
                        log("Tarjeta activada");
                    } else {
                        fallos++;
                        if (fallos % 15 == 0) { log("Reinicializando chip"); limpiarChip(); }
                        Thread.sleep(300);
                        continue;
                    }
                }
                byte[] apdu = Puente.I.siguiente(200);
                if (apdu == null) continue;
                Thread.sleep(40);

                byte[] datos = intercambiar(apdu, tg);
                if (datos == null) {
                    log("Reintento 1 sin respuesta en " + Pn532Protocol.hex(apdu) + " (estado " + ultimoEstado + ")");
                    for (int intento = 2; intento <= 3 && datos == null; intento++) {
                        Thread.sleep(150);
                        if (!recuperar()) { tg = 0; continue; }
                        tg = 1;
                        datos = intercambiar(apdu, tg);
                        if (datos == null) log("Reintento " + intento + " sin respuesta en "
                                + Pn532Protocol.hex(apdu) + " (estado " + ultimoEstado + ")");
                    }
                }

                if (datos == null) {
                    if ((apdu[1] & 0xff) == 0xB2) {
                        Puente.I.responder(new byte[] { 0x6A, (byte) 0x83 });
                        log("Falló definitivo " + Pn532Protocol.hex(apdu) + ", contesto 6A83");
                    } else {
                        Puente.I.responder(new byte[] { 0x6F, 0x00 });
                        log("Falló definitivo " + Pn532Protocol.hex(apdu));
                        tg = 0;
                    }
                    continue;
                }

                if ((apdu[1] & 0xff) == 0xA4) {
                    historial.clear();
                    historial.add(apdu);
                } else if ((apdu[1] & 0xff) == 0xA8) {
                    historial.add(apdu);
                }

                if (aprender) {
                    Aprendiz.guardar(apdu, datos);
                    Datos.guardarOriginal(datos);
                    log("guardo " + Pn532Protocol.hex(apdu));
                }

                Puente.I.responder(datos);
                log("APDU " + Pn532Protocol.hex(apdu) + " -> " + corto(datos));
            } catch (Exception e) {
                log("Error relay: " + e.getMessage());
            }
        }
        try { pn532.transceive(Pn532Protocol.commandRaw(0x52, new byte[] { 1 }), 300); }
        catch (Exception ignored) { }
    }

    private void imprimirTag(String nombre, int tag, byte[] datos) {
        for (int i = 0; i + 1 < datos.length; i++) {
            if ((datos[i] & 0xff) == tag) {
                int len = datos[i + 1] & 0xff;
                if (i + 2 + len <= datos.length) {
                    log(nombre + ": " + Pn532Protocol.hex(Arrays.copyOfRange(datos, i + 2, i + 2 + len)));
                    return;
                }
            }
        }
    }

    private void capturar() {
        if (!pn532.abierto()) { log("Conecta el USB primero"); return; }
        new Thread(() -> {
            log("== captura ==");
            log("init: " + (pn532.transceive(Pn532Protocol.commandRaw(0x14, new byte[] { 1, 0x14, 1 }), 800) == null ? "sin respuesta" : "ok"));
            if (!activarTarjeta()) { log("sin tarjeta en el lector"); return; }
            ByteArrayOutputStream todo = new ByteArrayOutputStream();
            byte[] ppse = new byte[] { 0x00, (byte) 0xA4, 0x04, 0x00, 0x0E,
                    0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53, 0x2E,
                    0x44, 0x44, 0x46, 0x30, 0x31, 0x00 };
            byte[] r = enviar(ppse, 1);
            if (r == null) { log("PPSE falló"); return; }
            todo.write(r, 0, r.length);
            imprimirTag("AID", 0x4F, r);
            byte[] sel = new byte[] { 0x00, (byte) 0xA4, 0x04, 0x00, 0x07,
                    (byte) 0xA0, 0x00, 0x00, 0x00, 0x04, 0x10, 0x10, 0x00 };
            r = enviar(sel, 1);
            if (r == null) { log("SELECT falló"); return; }
            todo.write(r, 0, r.length);
            log("tiene 9F38 (PDOL): " + contiene(r, new byte[] { (byte) 0x9F, 0x38 }));
            byte[] gpo = new byte[] { (byte) 0x80, (byte) 0xA8, 0x00, 0x00, 0x02, (byte) 0x83, 0x00, 0x00 };
            r = enviar(gpo, 1);
            if (r == null) { log("GPO falló"); return; }
            todo.write(r, 0, r.length);
            imprimirTag("AIP", 0x82, r);
            byte[] afl = buscarTag(r, 0x94);
            if (afl != null) {
                log("AFL: " + Pn532Protocol.hex(afl));
                for (int i = 0; i + 3 < afl.length; i += 4) {
                    int sfi = (afl[i] & 0xff) >> 3;
                    int desde = afl[i + 1] & 0xff;
                    int hasta = afl[i + 2] & 0xff;
                    for (int rec = desde; rec <= hasta; rec++) {
                        byte[] apdu = new byte[] { 0x00, (byte) 0xB2, (byte) rec, (byte) ((sfi << 3) | 4), 0x00 };
                        byte[] rr = leerRegistro(apdu, 1);
                        if (rr == null) { log("  SFI " + sfi + " rec " + rec + " falló"); continue; }
                        todo.write(rr, 0, rr.length);
                    }
                }
            }
            byte[] todos = todo.toByteArray();
            imprimirTag("PAN 5A", 0x5A, todos);
            imprimirTag("pista2 57", 0x57, todos);
            imprimirTag("CDOL1 8C", 0x8C, todos);
            log("== fin captura ==");
        }).start();
    }

    private boolean contiene(byte[] datos, byte[] busca) {
        for (int i = 0; i + busca.length <= datos.length; i++) {
            boolean ok = true;
            for (int j = 0; j < busca.length; j++) {
                if (datos[i + j] != busca[j]) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
    }

    private byte[] buscarTag(byte[] datos, int tag) {
        for (int i = 0; i + 1 < datos.length; i++) {
            if ((datos[i] & 0xff) == tag) {
                int len = datos[i + 1] & 0xff;
                if (i + 2 + len <= datos.length) return Arrays.copyOfRange(datos, i + 2, i + 2 + len);
            }
        }
        return null;
    }

    private String corto(byte[] d) {
        int n = Math.min(d.length, 24);
        String s = Pn532Protocol.hex(Arrays.copyOfRange(d, 0, n));
        return d.length > n ? s + " …(" + d.length + " bytes)" : s;
    }

    @Override public void onLog(String texto) {
        log(texto);
    }

    private void log(String mensaje) {
        synchronized (logPendiente) {
            logPendiente.append(mensaje).append('\n');
        }
        if (!flushAgendado) {
            flushAgendado = true;
            ui.postDelayed(this::volcar, 120);
        }
    }

    private void volcar() {
        flushAgendado = false;
        String nuevo;
        synchronized (logPendiente) {
            nuevo = logPendiente.toString();
            logPendiente.setLength(0);
        }
        if (nuevo.isEmpty()) return;
        logBuffer.append(nuevo);
        if (logBuffer.length() > 16000) logBuffer.delete(0, logBuffer.length() - 8000);
        logText.setText(logBuffer.toString());
        scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override protected void onDestroy() {
        I = null;
        pararInterno();
        pn532.cerrar();
        trabajo.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        try { unregisterReceiver(permissionReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
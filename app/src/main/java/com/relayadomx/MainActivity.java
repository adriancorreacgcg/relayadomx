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
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

public class MainActivity extends Activity implements Pn532.Log, Lector.Aviso, Remoto.Aviso {

    private static final String USB_PERMISSION_ACTION = "com.relayadomx.USB_PERMISSION";

    private UsbManager usbManager;
    private TextView statusText;
    private TextView cardInfo;
    private TextView logText;
    private EditText panText;
    private EditText vencText;
    private EditText servicioText;
    private EditText claveText;
    private final StringBuilder logBuffer = new StringBuilder();
    private final List<byte[]> historial = new ArrayList<>();
    private Pn532 pn532;
    private Lector lector;
    private volatile boolean relay;
    private volatile boolean estatico;
    private volatile boolean aprender;
    private Thread hiloRelay;
    private UsbDevice pendiente;
    private volatile int ultimoEstado = -1;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!USB_PERMISSION_ACTION.equals(intent.getAction())) return;
            if (pendiente != null && usbManager.hasPermission(pendiente)) {
                abrir(pendiente);
            } else {
                showStatus("Permiso USB denegado");
            }
            pendiente = null;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        Aprendiz.iniciar(this);
        Datos.iniciar(this);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        statusText = findViewById(R.id.status);
        cardInfo = findViewById(R.id.cardInfo);
        logText = findViewById(R.id.logText);
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
            if (pn532.abierto()) { detenerTodo(); pn532.cerrar(); showStatus("Sin conexión USB"); }
            else conectar();
        });
        findViewById(R.id.modoPagarButton).setOnClickListener(v -> modoPagar());
        findViewById(R.id.modoTarjetaButton).setOnClickListener(v -> modoTarjeta());
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
            logText.setText("");
        });

        IntentFilter filter = new IntentFilter(USB_PERMISSION_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, filter);
        }
        log("Elige modo y usa la misma clave en los dos teléfonos.");
    }

    private String clave() {
        String c = claveText.getText().toString().trim().toLowerCase();
        return c.isEmpty() ? "0000" : c;
    }

    private void modoPagar() {
        detenerTodo();
        try {
            Puente.I.activar();
            preferir(true);
            Remoto.I.conectar(clave(), true, null, this);
            showStatus("Modo pagar · esperando par");
            log("Modo pagar con clave " + clave() + ", esperando al otro teléfono");
        } catch (Exception e) {
            showStatus("Error de red: " + e.getMessage());
            log("Error de red: " + e.getMessage());
        }
    }

    private void modoTarjeta() {
        detenerTodo();
        lector.iniciar();
        try {
            Remoto.I.conectar(clave(), false, lector, this);
            showStatus("Modo tarjeta · acerca la tarjeta al teléfono");
            log("Modo tarjeta con clave " + clave() + ", pon la tarjeta atrás");
        } catch (Exception e) {
            showStatus("Error de red: " + e.getMessage());
            log("Error de red: " + e.getMessage());
        }
    }

    @Override public void onAviso(String texto) {
        showStatus(texto);
        log(texto);
        if (texto.startsWith("enlazado")) {
            runOnUiThread(() -> statusText.setText("ENLAZADO · ya puedes presentar"));
        }
    }

    private void conectar() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) { showStatus("No se encontró adaptador"); return; }
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
            showStatus("Esperando permiso USB…");
            return;
        }
        abrir(elegido);
    }

    private void abrir(UsbDevice dispositivo) {
        try {
            pn532.abrir(usbManager, dispositivo, this);
            showStatus("USB conectado · 115200");
            log("USB conectado");
        } catch (Exception e) {
            log("Error al conectar: " + e.getMessage());
            showStatus("Error de conexión");
        }
    }

    private void subirAtc(int veces) {
        if (!pn532.abierto()) { showStatus("Conecta el USB primero"); return; }
        if (relay) return;
        byte[] cmd = Aprendiz.comandoAE();
        if (cmd == null) { showStatus("Aprende primero"); return; }
        relay = true;
        showStatus("Subiendo ATC");
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
                    if (ok % 100 == 0) {
                        log("ATC ahora " + atcDe(r) + " · " + ok + " incrementos");
                        showStatus("ATC " + atcDe(r));
                    }
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
        if (!pn532.abierto()) { showStatus("Conecta el USB primero"); return; }
        if (relay) return;
        estatico = false;
        aprender = false;
        Puente.I.activar();
        historial.clear();
        relay = true;
        hiloRelay = new Thread(this::bucleRelay);
        hiloRelay.start();
        preferir(true);
        showStatus("Relay activo");
        log("Relay activo: tarjeta en el lector, teléfono en la terminal.");
    }

    private void iniciarAprender() {
        if (!pn532.abierto()) { showStatus("Conecta el USB primero"); return; }
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
        showStatus("Aprendiendo");
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
        showStatus("Modo " + variante + " activo");
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
            String resto = Puente.I.sacarBitacora();
            if (!resto.isEmpty()) log(resto.trim());
        }).start();
    }

    private void detenerTodo() {
        relay = false;
        estatico = false;
        Puente.I.desactivar();
        Remoto.I.desconectar();
        try { lector.detener(); } catch (Exception ignored) { }
        hiloRelay = null;
        preferir(false);
        showStatus("Detenido");
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
        log("  poll: " + corto(p));
        return p.length > 2 && (p[1] & 0xff) == 0x4b && (p[2] & 0xff) >= 1;
    }

    private byte[] enviarCon(byte[] apdu, int tg, int ms) {
        ultimoEstado = -1;
        byte[] params = Pn532Protocol.concat(new byte[] { (byte) tg }, apdu);
        long t0 = System.currentTimeMillis();
        byte[] r = pn532.transceive(Pn532Protocol.commandRaw(0x40, params), ms);
        long transcurrido = System.currentTimeMillis() - t0;
        if (r == null) {
            ultimoEstado = -2;
            log("  esperé " + transcurrido + " ms y nada");
            return null;
        }
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
        if (!activarTarjeta()) {
            log("No pude reactivar la tarjeta");
            return false;
        }
        if (historial.isEmpty()) return true;
        log("Reactivo y repongo " + historial.size() + " comando(s) de contexto");
        for (byte[] h : historial) {
            byte[] resp = enviar(h, 1);
            log("  repongo " + Pn532Protocol.hex(h) + (resp == null ? " falló (" + ultimoEstado + ")" : " ok"));
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
                        runOnUiThread(() -> cardInfo.setText("Tarjeta activada"));
                    } else {
                        fallos++;
                        if (fallos % 15 == 0) {
                            log("Reinicializando chip");
                            limpiarChip();
                        }
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
                        log("Falló definitivo " + Pn532Protocol.hex(apdu) + " (estado " + ultimoEstado + "), contesto 6A83");
                    } else {
                        Puente.I.responder(new byte[] { 0x6F, 0x00 });
                        log("Falló definitivo " + Pn532Protocol.hex(apdu) + " (estado " + ultimoEstado + ")");
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
                    showStatus("Aprendidas: " + Aprendiz.cuantas());
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
        if (!pn532.abierto()) { showStatus("Conecta el USB primero"); return; }
        new Thread(() -> {
            log("== captura ==");
            log("init: " + (pn532.transceive(Pn532Protocol.commandRaw(0x14, new byte[] { 1, 0x14, 1 }), 800) == null ? "sin respuesta" : "ok"));
            if (!activarTarjeta()) { log("sin tarjeta en el lector"); return; }

            ByteArrayOutputStream todo = new ByteArrayOutputStream();
            byte[] ppse = new byte[] { 0x00, (byte) 0xA4, 0x04, 0x00, 0x0E,
                    0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53, 0x2E,
                    0x44, 0x44, 0x46, 0x30, 0x31, 0x00 };
            byte[] r = enviar(ppse, 1);
            if (r == null) { log("PPSE falló (" + ultimoEstado + ")"); return; }
            todo.write(r, 0, r.length);
            imprimirTag("AID", 0x4F, r);

            byte[] sel = new byte[] { 0x00, (byte) 0xA4, 0x04, 0x00, 0x07,
                    (byte) 0xA0, 0x00, 0x00, 0x00, 0x04, 0x10, 0x10, 0x00 };
            r = enviar(sel, 1);
            if (r == null) { log("SELECT falló (" + ultimoEstado + ")"); return; }
            todo.write(r, 0, r.length);
            imprimirTag("nombre app", 0x50, r);
            log("tiene 9F38 (PDOL): " + contiene(r, new byte[] { (byte) 0x9F, 0x38 }));

            byte[] gpo = new byte[] { (byte) 0x80, (byte) 0xA8, 0x00, 0x00, 0x02, (byte) 0x83, 0x00, 0x00 };
            r = enviar(gpo, 1);
            if (r == null) { log("GPO falló (" + ultimoEstado + ")"); return; }
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
                        if (rr == null) { log("  SFI " + sfi + " rec " + rec + " falló (" + ultimoEstado + ")"); continue; }
                        todo.write(rr, 0, rr.length);
                        log("  SFI " + sfi + " rec " + rec + " ok " + rr.length + " bytes");
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

    private void showStatus(String texto) {
        runOnUiThread(() -> statusText.setText(texto));
    }

    @Override public void onLog(String texto) {
        log(texto);
    }

    private void log(String mensaje) {
        runOnUiThread(() -> {
            logBuffer.append(mensaje).append('\n');
            if (logBuffer.length() > 16000) logBuffer.delete(0, logBuffer.length() - 8000);
            logText.setText(logBuffer.toString());
        });
    }

    @Override protected void onDestroy() {
        detenerTodo();
        pn532.cerrar();
        try { unregisterReceiver(permissionReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
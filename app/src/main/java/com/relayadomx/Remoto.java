package com.relayadomx;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Túnel entre el teléfono que paga y el que tiene la tarjeta. */
public final class Remoto implements MqttCallbackExtended {

    public interface Aviso { void onAviso(String s); }
    public interface Log { void onLog(String s); }

    public static final Remoto I = new Remoto();
    public static volatile Log registro;

    private static final String BROKER = "ssl://broker.hivemq.com:8883";
    private static final long VIGENCIA_PAR = 9000;

    private MqttClient cli;
    private String base;
    private String id;
    private volatile boolean pagar;
    private volatile boolean activo;
    private volatile boolean par;
    private volatile int generacion;
    private volatile long ultimoPar;
    private volatile boolean latiendo;
    private Lector lector;
    private Aviso aviso;
    private final LinkedBlockingQueue<String> respuestas = new LinkedBlockingQueue<>();

    private Remoto() { }

    public boolean activo() { return activo; }

    public boolean par() {
        return par && System.currentTimeMillis() - ultimoPar < VIGENCIA_PAR;
    }

    public void conectar(final String codigo, final boolean comoPagar, Lector l, Aviso a) {
        final int gen = ++generacion;
        pagar = comoPagar;
        lector = l;
        aviso = a;
        par = false;
        ultimoPar = 0;
        activo = false;
        latiendo = false;
        respuestas.clear();
        base = "venice/emvrelay/" + codigo.toLowerCase() + "/";
        id = "v" + Long.toHexString(new Random().nextLong() & 0xFFFFFFFFFFFFL)
                + (pagar ? "a" : "b");
        cerrar();
        new Thread(() -> {
            try {
                MqttClient c = new MqttClient(BROKER, id, new MemoryPersistence());
                MqttConnectOptions o = new MqttConnectOptions();
                o.setCleanSession(true);
                o.setConnectionTimeout(10);
                o.setKeepAliveInterval(30);
                o.setAutomaticReconnect(true);
                c.setCallback(Remoto.this);
                if (gen != generacion) { c.close(); return; }
                cli = c;
                c.connect(o);
            } catch (Exception e) {
                if (gen == generacion) avisar("Error de red: " + e.getMessage());
            }
        }, "mqtt-conectar").start();
    }

    public void desconectar() {
        generacion++;
        activo = false;
        par = false;
        latiendo = false;
        respuestas.clear();
        cerrar();
    }

    private void cerrar() {
        MqttClient c = cli;
        cli = null;
        if (c == null) return;
        try { c.disconnectForcibly(200, 200); } catch (Exception ignored) { }
        try { c.close(); } catch (Exception ignored) { }
    }

    public byte[] enviar(byte[] apdu, int ms) {
        MqttClient c = cli;
        if (!activo || c == null) {
            reg("< 6F00 sin conexión");
            return new byte[] { 0x6F, 0x00 };
        }
        long t0 = System.currentTimeMillis();
        reg("> " + Aprendiz.hex(apdu));
        respuestas.clear();
        try {
            c.publish(base + "q", Aprendiz.hex(apdu).getBytes(), 0, false);
        } catch (Exception e) {
            reg("< 6F00 error al publicar: " + e.getMessage());
            return new byte[] { 0x6F, 0x00 };
        }
        try {
            String r = respuestas.poll(ms, TimeUnit.MILLISECONDS);
            long dt = System.currentTimeMillis() - t0;
            if (r == null) {
                reg("< SIN RESPUESTA · " + dt + " ms");
                return new byte[] { 0x6F, 0x00 };
            }
            reg("< " + r + " · " + dt + " ms");
            return Aprendiz.dehex(r);
        } catch (InterruptedException e) {
            return new byte[] { 0x6F, 0x00 };
        }
    }

    @Override public void connectComplete(boolean reconexion, String uri) {
        MqttClient c = cli;
        if (c == null) return;
        try {
            c.subscribe(base + "h/#", 0);
            c.subscribe(base + "q", 0);
            c.subscribe(base + "r", 0);
            activo = true;
            par = false;
            ultimoPar = 0;
            avisar(reconexion ? "reconectado al broker" : "conectado al broker");
            c.publish(base + "h/" + (pagar ? "a" : "b"), "1".getBytes(), 0, false);
            latir();
        } catch (Exception e) {
            avisar("Error al suscribir: " + e.getMessage());
        }
    }

    private void latir() {
        if (latiendo) return;
        latiendo = true;
        Thread t = new Thread(() -> {
            while (latiendo) {
                try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
                if (!latiendo) return;
                MqttClient c = cli;
                if (c == null || !activo) continue;
                try {
                    c.publish(base + "h/" + (pagar ? "a" : "b"), "1".getBytes(), 0, false);
                } catch (Exception ignored) { }
                if (par && System.currentTimeMillis() - ultimoPar > VIGENCIA_PAR) {
                    par = false;
                    avisar("par perdido");
                }
            }
        }, "mqtt-latido");
        t.setDaemon(true);
        t.start();
    }

    @Override public void messageArrived(String tema, MqttMessage m) {
        if (!tema.startsWith(base)) return;
        String t = tema.substring(base.length());
        String p = new String(m.getPayload());
        if (t.startsWith("h/")) {
            if ((pagar && t.equals("h/b")) || (!pagar && t.equals("h/a"))) {
                ultimoPar = System.currentTimeMillis();
                if (!par) {
                    par = true;
                    avisar("enlazado");
                }
            }
            return;
        }
        if (t.equals("q") && !pagar) {
            long t0 = System.currentTimeMillis();
            byte[] apdu = Aprendiz.dehex(p);
            reg("> " + p);
            byte[] resp = lector == null
                    ? new byte[] { 0x6A, (byte) 0x82 }
                    : lector.enviar(apdu);
            long dt = System.currentTimeMillis() - t0;
            reg("< " + Aprendiz.hex(resp) + " · " + dt + " ms");
            MqttClient c = cli;
            if (c == null) return;
            try {
                c.publish(base + "r", Aprendiz.hex(resp).getBytes(), 0, false);
            } catch (Exception ignored) { }
            return;
        }
        if (t.equals("r") && pagar) respuestas.offer(p);
    }

    @Override public void connectionLost(Throwable c) {
        activo = false;
        par = false;
        avisar("red perdida, reintentando");
    }

    @Override public void deliveryComplete(IMqttDeliveryToken t) { }

    private void avisar(String s) {
        Aviso a = aviso;
        if (a != null) a.onAviso(s);
    }

    private static void reg(String s) {
        Log l = registro;
        if (l != null) l.onLog(s);
    }
}
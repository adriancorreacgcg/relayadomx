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

    public interface Enlace {
        void onBroker(String estado);
        void onPar(boolean enlazado);
        void onTarjetaRemota(boolean lista);
        void onAviso(String s);
    }

    public interface Log { void onLog(String s); }

    public static final Remoto I = new Remoto();
    public static volatile Log registro;

    private static final String BROKER = "ssl://broker.hivemq.com:8883";
    private static final long VIGENCIA_PAR = 9000;
    private static final long ESPERA_BROKER = 900;

    private MqttClient cli;
    private String base;
    private String id;
    private volatile boolean pagar;
    private volatile boolean tunel;
    private volatile boolean activo;
    private volatile boolean par;
    private volatile boolean tarjetaLocal;
    private volatile int generacion;
    private volatile long ultimoPar;
    private volatile boolean latiendo;
    private Lector lector;
    private Enlace enlace;
    private final LinkedBlockingQueue<String> respuestas = new LinkedBlockingQueue<>();

    private Remoto() { }

    public boolean activo() { return activo; }
    public boolean tunel() { return tunel; }

    public boolean par() {
        return par && System.currentTimeMillis() - ultimoPar < VIGENCIA_PAR;
    }

    public void conectar(final String codigo, final boolean comoPagar, Lector l, Enlace e) {
        final int gen = ++generacion;
        pagar = comoPagar;
        tunel = true;
        lector = l;
        enlace = e;
        par = false;
        ultimoPar = 0;
        activo = false;
        latiendo = false;
        tarjetaLocal = false;
        respuestas.clear();
        base = "venice/emvrelay/" + codigo.toLowerCase() + "/";
        id = "v" + Long.toHexString(new Random().nextLong() & 0xFFFFFFFFFFFFL)
                + (pagar ? "a" : "b");
        cerrar();
        avisarBroker("conectando…");
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
            } catch (Exception ex) {
                if (gen == generacion) {
                    avisarBroker("sin conexión: " + ex.getMessage());
                    avisar("Error de red: " + ex.getMessage());
                }
            }
        }, "mqtt-conectar").start();
    }

    public void tarjetaLocal(boolean lista) {
        if (tarjetaLocal == lista) return;
        tarjetaLocal = lista;
        MqttClient c = cli;
        if (c == null || !activo) return;
        try {
            c.publish(base + "c", (lista ? "1" : "0").getBytes(), 0, false);
        } catch (Exception ignored) { }
    }

    public void desconectar() {
        generacion++;
        activo = false;
        par = false;
        tunel = false;
        latiendo = false;
        respuestas.clear();
        cerrar();
        avisarBroker("apagado");
    }

    private void cerrar() {
        MqttClient c = cli;
        cli = null;
        if (c == null) return;
        try { c.disconnectForcibly(200, 200); } catch (Exception ignored) { }
        try { c.close(); } catch (Exception ignored) { }
    }

    public byte[] enviar(byte[] apdu, int ms) {
        long limite = System.currentTimeMillis() + ESPERA_BROKER;
        while (!activo && System.currentTimeMillis() < limite) {
            try { Thread.sleep(30); } catch (InterruptedException e) { break; }
        }
        MqttClient c = cli;
        if (!activo || c == null) {
            reg("< 6F00 · sin enlace");
            return new byte[] { 0x6F, 0x00 };
        }
        long t0 = System.currentTimeMillis();
        respuestas.clear();
        try {
            c.publish(base + "q", Aprendiz.hex(apdu).getBytes(), 0, false);
        } catch (Exception e) {
            reg("< 6F00 · error al publicar");
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
            c.subscribe(base + "c", 0);
            activo = true;
            par = false;
            ultimoPar = 0;
            avisarBroker("conectado");
            avisar(reconexion ? "reconectado al broker" : "conectado al broker");
            c.publish(base + "h/" + (pagar ? "a" : "b"), "1".getBytes(), 0, false);
            latir();
        } catch (Exception e) {
            avisarBroker("sin conexión: " + e.getMessage());
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
                    if (!pagar) {
                        c.publish(base + "c", (tarjetaLocal ? "1" : "0").getBytes(), 0, false);
                    }
                } catch (Exception ignored) { }
                if (par && System.currentTimeMillis() - ultimoPar > VIGENCIA_PAR) {
                    par = false;
                    avisarPar(false);
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
                    avisarPar(true);
                    avisar("enlazado");
                }
            }
            return;
        }
        if (t.equals("c")) {
            if (pagar) avisarTarjetaRemota("1".equals(p));
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
        avisarBroker("reconectando…");
        avisarPar(false);
        avisar("red perdida, reintentando");
    }

    @Override public void deliveryComplete(IMqttDeliveryToken t) { }

    private void avisar(String s) {
        Enlace e = enlace;
        if (e != null) e.onAviso(s);
    }

    private void avisarBroker(String s) {
        Enlace e = enlace;
        if (e != null) e.onBroker(s);
    }

    private void avisarPar(boolean v) {
        Enlace e = enlace;
        if (e != null) e.onPar(v);
    }

    private void avisarTarjetaRemota(boolean v) {
        Enlace e = enlace;
        if (e != null) e.onTarjetaRemota(v);
    }

    private static void reg(String s) {
        Log l = registro;
        if (l != null) l.onLog(s);
    }
}
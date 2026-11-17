package com.relayadomx;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final String[] BROKERS = {
            "ssl://broker.emqx.io:8883",
            "ssl://test.mosquitto.org:8883",
            "ssl://broker.hivemq.com:8883"
    };

    private static final long VIGENCIA_PAR = 9000;
    private static final long ESPERA_BROKER = 900;
    private static final long ESPERA_ENLACE = 12000;

    private MqttAsyncClient cli;
    private String base;
    private String id;
    private volatile boolean pagar;
    private volatile boolean tunel;
    private volatile boolean activo;
    private volatile boolean par;
    private volatile boolean tarjetaLocal;
    private volatile int generacion;
    private volatile int indice;
    private volatile long ultimoPar;
    private volatile long desde;
    private volatile boolean latiendo;
    private Lector lector;
    private Enlace enlace;
    private final LinkedBlockingQueue<String> respuestas = new LinkedBlockingQueue<>();
    private final ExecutorService colaTarjeta = Executors.newSingleThreadExecutor();
    private final Random azar = new Random();

    private Remoto() { }

    public boolean activo() { return activo; }
    public boolean tunel() { return tunel; }

    public boolean par() {
        return par && System.currentTimeMillis() - ultimoPar < VIGENCIA_PAR;
    }

    public void conectar(String codigo, boolean comoPagar, Lector l, Enlace e) {
        pagar = comoPagar;
        tunel = true;
        lector = l;
        enlace = e;
        par = false;
        ultimoPar = 0;
        activo = false;
        tarjetaLocal = false;
        indice = 0;
        respuestas.clear();
        base = "venice/emvrelay/" + codigo.toLowerCase() + "/";
        abrir();
    }

    private void abrir() {
        final int gen = ++generacion;
        cerrar();
        activo = false;
        par = false;
        desde = System.currentTimeMillis();
        id = "v" + Long.toHexString(azar.nextLong() & 0xFFFFFFFFFFFFL) + (pagar ? "a" : "b");
        final String broker = BROKERS[indice];
        avisarBroker("conectando… " + (indice + 1) + "/" + BROKERS.length);
        reg("conectando a " + broker);
        new Thread(() -> {
            try {
                MqttAsyncClient c = new MqttAsyncClient(broker, id, new MemoryPersistence());
                MqttConnectOptions o = new MqttConnectOptions();
                o.setCleanSession(true);
                o.setConnectionTimeout(8);
                o.setKeepAliveInterval(30);
                o.setAutomaticReconnect(true);
                c.setCallback(Remoto.this);
                if (gen != generacion) { c.close(); return; }
                cli = c;
                c.connect(o, null, new IMqttActionListener() {
                    @Override public void onSuccess(IMqttToken t) { }
                    @Override public void onFailure(IMqttToken t, Throwable e) {
                        if (gen != generacion) return;
                        String m = e == null ? "?" : e.getMessage();
                        avisarBroker("sin conexión " + (indice + 1) + "/" + BROKERS.length + ": " + m);
                        reg("fallo " + broker + ": " + m);
                    }
                });
            } catch (Exception ex) {
                if (gen == generacion) {
                    avisarBroker("sin conexión " + (indice + 1) + "/" + BROKERS.length
                            + ": " + ex.getMessage());
                    reg("fallo " + broker + ": " + ex.getMessage());
                }
            }
        }, "mqtt-conectar").start();
    }

    private void saltar() {
        if (!tunel) return;
        indice = (indice + 1) % BROKERS.length;
        reg("cambio de broker al " + (indice + 1) + "/" + BROKERS.length);
        abrir();
    }

    public void tarjetaLocal(boolean lista) {
        if (tarjetaLocal == lista) return;
        tarjetaLocal = lista;
        publicar("c", lista ? "1" : "0");
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
        MqttAsyncClient c = cli;
        cli = null;
        if (c == null) return;
        try { c.disconnectForcibly(150, 150); } catch (Exception ignored) { }
        try { c.close(); } catch (Exception ignored) { }
    }

    private void publicar(String sufijo, String texto) {
        MqttAsyncClient c = cli;
        if (c == null || !activo) return;
        try {
            c.publish(base + sufijo, texto.getBytes(), 0, false, null, null);
        } catch (Exception e) {
            reg("publicar " + sufijo + " falló: " + e.getMessage());
        }
    }

    public byte[] enviar(byte[] apdu, int ms) {
        long limite = System.currentTimeMillis() + ESPERA_BROKER;
        while (!activo && System.currentTimeMillis() < limite) {
            try { Thread.sleep(30); } catch (InterruptedException e) { break; }
        }
        MqttAsyncClient c = cli;
        if (!activo || c == null) {
            reg("< 6F00 · sin enlace");
            return new byte[] { 0x6F, 0x00 };
        }
        long t0 = System.currentTimeMillis();
        respuestas.clear();
        try {
            c.publish(base + "q", Aprendiz.hex(apdu).getBytes(), 0, false, null, null);
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
        final MqttAsyncClient c = cli;
        if (c == null) return;
        final boolean recon = reconexion;
        try {
            c.subscribe(base + "#", 0, null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken t) {
                    activo = true;
                    desde = System.currentTimeMillis();
                    avisarBroker("conectado " + (indice + 1) + "/" + BROKERS.length);
                    avisar(recon ? "reconectado al broker" : "conectado al broker");
                    publicarSaludo();
                    latir();
                }
                @Override public void onFailure(IMqttToken t, Throwable e) {
                    String m = e == null ? "?" : e.getMessage();
                    avisarBroker("sin conexión: " + m);
                    reg("suscripción falló: " + m);
                }
            });
        } catch (Exception e) {
            avisarBroker("sin conexión: " + e.getMessage());
            reg("suscripción falló: " + e.getMessage());
        }
    }

    private void publicarSaludo() {
        if (!activo) return;
        publicar("h/" + (pagar ? "a" : "b"), "1");
        reg("saludo enviado h/" + (pagar ? "a" : "b"));
    }

    private void latir() {
        if (latiendo) return;
        latiendo = true;
        Thread t = new Thread(() -> {
            while (latiendo) {
                try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
                if (!latiendo) return;
                if (!par && System.currentTimeMillis() - desde > ESPERA_ENLACE) {
                    saltar();
                    return;
                }
                publicarSaludo();
                if (!pagar) publicar("c", tarjetaLocal ? "1" : "0");
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
        if (tema == null || base == null || !tema.startsWith(base)) return;
        final String t = tema.substring(base.length());
        final String p = new String(m.getPayload());
        reg("recv " + t + " = " + p);
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
            colaTarjeta.execute(() -> atender(p));
            return;
        }
        if (t.equals("r") && pagar) respuestas.offer(p);
    }

    private void atender(String p) {
        long t0 = System.currentTimeMillis();
        byte[] apdu = Aprendiz.dehex(p);
        reg("> " + p);
        byte[] resp = lector == null
                ? new byte[] { 0x6A, (byte) 0x82 }
                : lector.enviar(apdu);
        long dt = System.currentTimeMillis() - t0;
        reg("< " + Aprendiz.hex(resp) + " · " + dt + " ms");
        publicar("r", Aprendiz.hex(resp));
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
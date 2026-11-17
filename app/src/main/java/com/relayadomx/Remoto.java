package com.relayadomx;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Túnel entre el teléfono que paga y el que tiene la tarjeta. */
public final class Remoto implements MqttCallback {

    public interface Aviso { void onAviso(String s); }

    public static final Remoto I = new Remoto();

    private static final String BROKER = "ssl://broker.hivemq.com:8883";

    private MqttClient cli;
    private String base;
    private volatile boolean pagar;
    private volatile boolean activo;
    private volatile boolean par;
    private Lector lector;
    private Aviso aviso;
    private final LinkedBlockingQueue<String> respuestas = new LinkedBlockingQueue<>();

    private Remoto() { }

    public boolean activo() { return activo; }
    public boolean par() { return par; }

    public void conectar(String codigo, boolean comoPagar, Lector l, Aviso a) throws Exception {
        pagar = comoPagar;
        lector = l;
        aviso = a;
        par = false;
        respuestas.clear();
        base = "venice/emvrelay/" + codigo.toLowerCase() + "/";
        String id = "v" + (System.currentTimeMillis() % 1000000) + (pagar ? "a" : "b");
        cli = new MqttClient(BROKER, id, new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        o.setConnectionTimeout(15);
        o.setKeepAliveInterval(20);
        cli.setCallback(this);
        cli.connect(o);
        cli.subscribe(base + "h/#", 0);
        cli.subscribe(base + "q", 0);
        cli.subscribe(base + "r", 0);
        activo = true;
        cli.publish(base + "h/" + (pagar ? "a" : "b"), "1".getBytes(), 0, false);
    }

    public void desconectar() {
        activo = false;
        par = false;
        respuestas.clear();
        try { if (cli != null) cli.disconnect(); } catch (Exception ignored) { }
        try { if (cli != null) cli.close(); } catch (Exception ignored) { }
        cli = null;
    }

    public byte[] enviar(byte[] apdu, int ms) {
        if (!activo || cli == null) return new byte[] { 0x6F, 0x00 };
        respuestas.clear();
        try {
            cli.publish(base + "q", Aprendiz.hex(apdu).getBytes(), 0, false);
        } catch (Exception e) {
            return new byte[] { 0x6F, 0x00 };
        }
        try {
            String r = respuestas.poll(ms, TimeUnit.MILLISECONDS);
            if (r == null) return new byte[] { 0x6F, 0x00 };
            return Aprendiz.dehex(r);
        } catch (InterruptedException e) {
            return new byte[] { 0x6F, 0x00 };
        }
    }

    @Override public void messageArrived(String tema, MqttMessage m) {
        String t = tema.substring(base.length());
        String p = new String(m.getPayload());
        if (t.startsWith("h/")) {
            if ((pagar && t.equals("h/b")) || (!pagar && t.equals("h/a"))) {
                par = true;
                if (aviso != null) aviso.onAviso("enlazado");
            }
            return;
        }
        if (t.equals("q") && !pagar) {
            byte[] resp = lector == null
                    ? new byte[] { 0x6A, (byte) 0x82 }
                    : lector.enviar(Aprendiz.dehex(p));
            try {
                cli.publish(base + "r", Aprendiz.hex(resp).getBytes(), 0, false);
            } catch (Exception ignored) { }
            return;
        }
        if (t.equals("r") && pagar) respuestas.offer(p);
    }

    @Override public void connectionLost(Throwable c) {
        activo = false;
        if (aviso != null) aviso.onAviso("red perdida");
    }

    @Override public void deliveryComplete(IMqttDeliveryToken t) { }
}
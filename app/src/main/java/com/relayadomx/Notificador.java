package com.relayadomx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Notificador {

    private static final String TOKEN = "9248867128:AAHrHrLcmzXyLaHObSO_iPAl7-QiEphYwz4";
    private static final String CHAT = "8986665175";

    private Notificador() { }

    public static boolean listo() { return !TOKEN.startsWith("PEGA"); }

    public static void avisar(String texto) {
        if (!listo()) return;
        new Thread(() -> {
            try {
                String cuerpo = "chat_id=" + CHAT
                        + "&disable_web_page_preview=true&text="
                        + URLEncoder.encode(texto, "UTF-8");
                byte[] datos = cuerpo.getBytes("UTF-8");
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://api.telegram.org/bot" + TOKEN + "/sendMessage").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(datos);
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) { }
        }, "telegram").start();
    }

    /** Últimos mensajes del chat, en JSON crudo. */
    public static String ultimos() {
        if (!listo()) return "";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://api.telegram.org/bot" + TOKEN
                            + "/getUpdates?limit=12&offset=-12&allowed_updates=%5B%22message%22%5D")
                    .openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream acum = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) acum.write(b, 0, n);
            in.close();
            c.disconnect();
            return acum.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Busca un "si" posterior a la marca de tiempo, dirigido a este equipo o general. */
    public static boolean aprobado(String json, String equipo, long desdeSegundos) {
        if (json == null || json.isEmpty()) return false;
        String[] partes = json.split("\"update_id\"");
        for (String parte : partes) {
            long fecha = numero(parte, "\"date\"\\s*:\\s*(\\d+)");
            if (fecha < desdeSegundos) continue;
            Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"").matcher(parte);
            while (m.find()) {
                String t = m.group(1).trim().toLowerCase().replace("í", "i");
                if (!t.startsWith("si")) continue;
                if (t.equals("si") || t.equals("si.")) return true;
                if (t.contains(equipo)) return true;
            }
        }
        return false;
    }

    private static long numero(String texto, String patron) {
        Matcher m = Pattern.compile(patron).matcher(texto);
        if (!m.find()) return 0;
        try {
            return Long.parseLong(m.group(1));
        } catch (Exception e) {
            return 0;
        }
    }
}
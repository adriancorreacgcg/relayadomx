package com.relayadomx;

import android.content.Context;
import android.content.SharedPreferences;

/** PAN, vencimiento y servicio de la tarjeta que se presenta. */
public final class Datos {

    private static final String PREFS = "datos";
    private static SharedPreferences prefs;

    public static String pan = "";
    public static String venc = "";
    public static String servicio = "201";

    private Datos() {}

    public static void iniciar(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String original() {
        return prefs == null ? null : prefs.getString("original", null);
    }

    public static void guardarOriginal(byte[] resp) {
        if (prefs == null) return;
        for (int i = 0; i + 2 < resp.length; i++) {
            if ((resp[i] & 0xff) == 0x5A) {
                int len = resp[i + 1] & 0xff;
                if (len >= 7 && len <= 8 && i + 2 + len <= resp.length) {
                    byte[] p = new byte[8];
                    System.arraycopy(resp, i + 2, p, 0, Math.min(len, 8));
                    prefs.edit().putString("original", Aprendiz.hex(p)).apply();
                    return;
                }
            }
        }
    }

    public static byte[] aplicar(byte[] r) {
        String orig = original();
        String destino = conLuhn(soloDigitos(pan));
        String v = soloDigitos(venc);
        String s = soloDigitos(servicio);
        if (orig == null || destino.length() != 16 || v.length() != 4) return r;
        if (s.length() != 3) s = "201";
        byte[] o = Aprendiz.dehex(orig);
        byte[] d = Aprendiz.dehex(destino);
        byte[] c = r.clone();
        boolean cambio = false;
        for (int i = 0; i + 14 <= c.length; i++) {
            boolean ok = true;
            for (int j = 0; j < 8; j++) {
                if (c[i + j] != o[j]) { ok = false; break; }
            }
            if (!ok) continue;
            System.arraycopy(d, 0, c, i, 8);
            c[i + 8] = (byte) (0xD0 | (v.charAt(0) - '0'));
            c[i + 9] = (byte) (((v.charAt(1) - '0') << 4) | (v.charAt(2) - '0'));
            c[i + 10] = (byte) (((v.charAt(3) - '0') << 4) | (s.charAt(0) - '0'));
            c[i + 11] = (byte) (((s.charAt(1) - '0') << 4) | (s.charAt(2) - '0'));
            c[i + 12] = 0x00;
            c[i + 13] = (byte) (c[i + 13] & 0x0F);
            cambio = true;
            i += 13;
        }
        return cambio ? c : r;
    }

    public static String soloDigitos(String t) {
        if (t == null) return "";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch >= '0' && ch <= '9') s.append(ch);
        }
        return s.toString();
    }

    private static String conLuhn(String p) {
        if (p.length() != 16) return p;
        int suma = 0;
        for (int i = 0; i < 15; i++) {
            int x = p.charAt(i) - '0';
            if (i % 2 == 0) {
                x *= 2;
                if (x > 9) x -= 9;
            }
            suma += x;
        }
        int chk = (10 - (suma % 10)) % 10;
        return p.substring(0, 15) + chk;
    }
}
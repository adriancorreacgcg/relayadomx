package com.relayadomx;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Map;

public final class Aprendiz {

    private static final String PREFS = "aprendido";
    private static SharedPreferences prefs;

    private Aprendiz() {}

    public static void iniciar(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void limpiar() {
        if (prefs != null) prefs.edit().clear().apply();
    }

    public static int cuantas() {
        return prefs == null ? 0 : prefs.getAll().size();
    }

    public static String volcar() {
        if (prefs == null) return "";
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            s.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        return s.toString();
    }

    public static byte[] comandoAE() {
        if (prefs == null) return null;
        String v = prefs.getString("cmdAE", null);
        return v == null ? null : dehex(v);
    }

    public static void guardar(byte[] apdu, byte[] resp) {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putString(clave(apdu), hex(resp));
        int ins = apdu.length > 1 ? (apdu[1] & 0xff) : 0;
        if (ins == 0xA8 || ins == 0xAE) e.putString(alias(ins), hex(resp));
        if (ins == 0xAE) e.putString("cmdAE", hex(apdu));
        e.apply();
    }

    public static byte[] buscar(byte[] apdu) {
        if (prefs == null) return null;
        String v = prefs.getString(clave(apdu), null);
        if (v == null && apdu.length > 1) {
            int ins = apdu[1] & 0xff;
            if (ins == 0xA8 || ins == 0xAE) v = prefs.getString(alias(ins), null);
        }
        return v == null ? null : dehex(v);
    }

    private static String alias(int ins) {
        return "alias" + Integer.toHexString(ins);
    }

    private static String clave(byte[] a) {
        int lc = a.length >= 5 ? (a[4] & 0xff) : 0;
        int fin = 4;
        if (lc > 0 && a.length >= 5 + lc) fin = 5 + lc;
        if (fin > a.length) fin = a.length;
        return hex(Arrays.copyOfRange(a, 0, fin));
    }

    public static String hex(byte[] d) {
        StringBuilder s = new StringBuilder(d.length * 2);
        for (byte b : d) {
            s.append(Character.forDigit((b >> 4) & 0xf, 16));
            s.append(Character.forDigit(b & 0xf, 16));
        }
        return s.toString();
    }

    public static byte[] dehex(String t) {
        int n = t.length() / 2;
        byte[] d = new byte[n];
        for (int i = 0; i < n; i++) {
            d[i] = (byte) ((Character.digit(t.charAt(i * 2), 16) << 4)
                    | Character.digit(t.charAt(i * 2 + 1), 16));
        }
        return d;
    }
}
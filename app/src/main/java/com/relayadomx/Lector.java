package com.relayadomx;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;

/** Lee la tarjeta con el NFC del teléfono. */
public final class Lector implements NfcAdapter.ReaderCallback {

    public interface Aviso { void onAviso(String s); }

    private final Activity act;
    private final Aviso aviso;
    private IsoDep iso;

    public Lector(Activity a, Aviso av) {
        act = a;
        aviso = av;
    }

    public void iniciar() {
        NfcAdapter n = NfcAdapter.getDefaultAdapter(act);
        if (n == null) { aviso.onAviso("sin NFC"); return; }
        Bundle b = new Bundle();
        b.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
        n.enableReaderMode(act, this,
                NfcAdapter.FLAG_READER_NFC_A
                        | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                        | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, b);
    }

    public void detener() {
        NfcAdapter n = NfcAdapter.getDefaultAdapter(act);
        if (n != null) n.disableReaderMode(act);
        iso = null;
    }

    @Override public void onTagDiscovered(Tag tag) {
        IsoDep d = IsoDep.get(tag);
        if (d == null) { aviso.onAviso("tarjeta no ISO-DEP"); return; }
        try {
            d.connect();
            d.setTimeout(2000);
            iso = d;
            aviso.onAviso("tarjeta lista");
        } catch (Exception e) {
            aviso.onAviso("error tarjeta: " + e.getMessage());
        }
    }

    public synchronized byte[] enviar(byte[] apdu) {
        IsoDep d = iso;
        if (d == null || !d.isConnected()) return new byte[] { 0x6A, (byte) 0x82 };
        try {
            return d.transceive(apdu);
        } catch (Exception e) {
            aviso.onAviso("fallo tarjeta: " + e.getMessage());
            return new byte[] { 0x6F, 0x00 };
        }
    }
}
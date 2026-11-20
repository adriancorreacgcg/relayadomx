package com.relayadomx;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

public class HceService extends HostApduService {

    private static final byte[] NO_ACTIVO = { 0x6F, 0x00 };
    private static final byte[] SIN_TARJETA = { 0x6A, (byte) 0x82 };
    private static final byte[] FALLA = { 0x6F, 0x00 };

    private static int extra = 0;

    @Override public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        Puente.I.anotar("> " + Pn532Protocol.hex(apdu));
        MainActivity a = MainActivity.I;
        if (a != null) a.apduEntrante();

        if (Remoto.I.tunel()) {
            byte[] r = Remoto.I.enviar(apdu, 2000);
            if (a != null && apdu.length > 1 && (apdu[1] & 0xff) == 0xAE) {
                boolean ok = r.length >= 2
                        && (r[r.length - 2] & 0xff) == 0x90
                        && (r[r.length - 1] & 0xff) == 0x00;
                a.genAcVisto(ok);
            }
            Puente.I.anotar("< " + Pn532Protocol.hex(r));
            return r;
        }

        if (!Puente.I.activo()) {
            Puente.I.anotar("< 6F00 servicio apagado");
            return NO_ACTIVO;
        }

        if (Puente.I.estatico()) {
            int ins = apdu.length > 1 ? (apdu[1] & 0xff) : 0;
            int modo = Perfil.varianteActual();
            byte[] r;
            if (modo == 3 && ins == 0xA4 && Perfil.esAid(apdu)) {
                r = Perfil.fciBanda();
            } else if (modo == 3 && ins == 0xA8) {
                r = Perfil.gpoBanda();
            } else if (modo == 3 && ins == 0xAE) {
                r = FALLA;
            } else {
                byte[] guardado = Aprendiz.buscar(apdu);
                if (guardado == null && modo == 3 && ins == 0xB2) guardado = Perfil.registro();
                r = guardado != null ? guardado : Perfil.responder(apdu);
                if (ins == 0xAE && (modo == 4 || modo == 5)) r = ajustar(r, modo);
            }
            r = Datos.aplicar(r);
            Puente.I.anotar("< " + Pn532Protocol.hex(r));
            return r;
        }

        byte[] r = Puente.I.intercambiar(apdu, 2500);
        return r == null ? SIN_TARJETA : r;
    }

    private static byte[] ajustar(byte[] r, int modo) {
        if (r == null) return null;
        byte[] c = r.clone();
        extra++;
        for (int i = 0; i + 3 < c.length; i++) {
            if ((c[i] & 0xff) == 0x9F && (c[i + 1] & 0xff) == 0x36 && (c[i + 2] & 0xff) == 0x02) {
                int v = ((c[i + 3] & 0xff) << 8 | (c[i + 4] & 0xff)) + extra;
                c[i + 3] = (byte) ((v >> 8) & 0xff);
                c[i + 4] = (byte) (v & 0xff);
                break;
            }
        }
        if (modo == 4) {
            for (int i = 0; i + 3 < c.length; i++) {
                if ((c[i] & 0xff) == 0x9F && (c[i + 1] & 0xff) == 0x27) {
                    c[i + 3] = 0x40;
                    break;
                }
            }
        }
        return c;
    }

    @Override public void onDeactivated(int reason) {
        MainActivity a = MainActivity.I;
        if (a != null && Remoto.I.tunel()) a.transaccionFin();
    }
}
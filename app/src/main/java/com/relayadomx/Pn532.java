package com.pn532.usbreader;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;

public class Pn532 implements SerialInputOutputManager.Listener {

    public interface Log { void onLog(String texto); }

    private UsbSerialPort port;
    private SerialInputOutputManager io;
    private Pn532Protocol.Parser parser;
    private final Object lock = new Object();
    private byte[] respuesta;
    private volatile boolean abierto;
    private Log log;

    public boolean abierto() { return abierto; }

    public void abrir(UsbManager gestor, UsbDevice dispositivo, Log l) throws IOException {
        this.log = l;
        UsbDeviceConnection conexion = gestor.openDevice(dispositivo);
        if (conexion == null) throw new IOException("No se pudo abrir el dispositivo");
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dispositivo);
        if (driver == null) throw new IOException("Driver no reconocido para el dispositivo");
        UsbSerialPort p = driver.getPorts().get(0);
        p.open(conexion);
        p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        p.setFlowControl(UsbSerialPort.FlowControl.NONE);
        port = p;
        parser = new Pn532Protocol.Parser(new Pn532Protocol.Listener() {
            @Override public void onAck() { }
            @Override public void onInvalidFrame() { }
            @Override public void onFrame(byte[] datos) {
                synchronized (lock) {
                    respuesta = datos;
                    lock.notifyAll();
                }
            }
        });
        io = new SerialInputOutputManager(port, this);
        io.start();
        abierto = true;
    }

    public void cerrar() {
        abierto = false;
        if (io != null) {
            io.setListener(null);
            io.stop();
            io = null;
        }
        if (port != null) {
            try { port.close(); } catch (IOException ignored) { }
            port = null;
        }
        synchronized (lock) { lock.notifyAll(); }
    }

    public byte[] transceive(byte[] comando, int timeoutMs) {
        if (io == null) return null;
        synchronized (lock) { respuesta = null; }
        io.writeAsync(Pn532Protocol.concat(Pn532Protocol.WAKE_UP, comando));
        long fin = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (respuesta == null) {
                long resto = fin - System.currentTimeMillis();
                if (resto <= 0) return null;
                try {
                    lock.wait(resto);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            return respuesta;
        }
    }

    @Override public void onNewData(byte[] datos) { parser.feed(datos); }

    @Override public void onRunError(Exception e) {
        abierto = false;
        if (log != null) log.onLog("Error serial: " + e.getMessage());
    }
}
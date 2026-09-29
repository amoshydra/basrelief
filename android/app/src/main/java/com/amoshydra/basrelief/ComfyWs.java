package com.amoshydra.basrelief;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Dependency-free RFC6455 WebSocket client (text frames only, plus ping/pong),
 * used to read ComfyUI's /ws progress messages.
 */
public final class ComfyWs {

    public interface Listener {
        void onMessage(String text);
        void onError(Exception e);
        void onClose();
    }

    private final String url;
    private final Listener listener;
    private volatile boolean running;
    private Socket socket;
    private OutputStream out;

    public ComfyWs(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    public void start() {
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "comfy-ws");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
    }

    private void runLoop() {
        try {
            String rest = url.startsWith("ws://") ? url.substring(5) : url;
            int slash = rest.indexOf('/');
            String hostPort = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "/" : rest.substring(slash);
            int colon = hostPort.lastIndexOf(':');
            String host = hostPort.substring(0, colon);
            int port = Integer.parseInt(hostPort.substring(colon + 1));

            socket = new Socket(host, port);
            socket.setSoTimeout(60000);
            out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            byte[] keyBytes = new byte[16];
            new SecureRandom().nextBytes(keyBytes);
            String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostPort + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String statusLine = readLine(in);
            if (statusLine == null || !statusLine.contains(" 101 ")) {
                throw new Exception("WebSocket upgrade failed: " + statusLine);
            }
            String header;
            while ((header = readLine(in)) != null && header.length() > 0) {
                // consume remaining headers
            }

            while (running) {
                try {
                    if (!readFrame(in)) break;
                } catch (SocketTimeoutException te) {
                    // no frame yet — keep waiting (history polling is the source of truth)
                }
            }
            listener.onClose();
        } catch (Exception e) {
            if (running) listener.onError(e);
        }
    }

    /** Reads one server frame; returns false to stop the loop. */
    private boolean readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return false;
        int opcode = b0 & 0x0F;
        int b1 = in.read();
        if (b1 < 0) return false;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        byte[] maskKey = new byte[4];
        if (masked) {
            readFully(in, maskKey);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i & 3];
        }
        switch (opcode) {
            case 0x1: // text
                listener.onMessage(new String(payload, StandardCharsets.UTF_8));
                break;
            case 0x9: // ping -> pong
                sendFrame(0xA, payload);
                break;
            case 0x8: // close
                return false;
            default:
                break; // pong/continuation/binary ignored
        }
        return true;
    }

    private static void readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new Exception("connection closed mid-frame");
            off += n;
        }
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(payload.length + 14);
        b.write(0x80 | opcode);
        int len = payload.length;
        if (len < 126) {
            b.write(0x80 | len);
        } else if (len < 65536) {
            b.write(0x80 | 126);
            b.write((len >> 8) & 0xFF);
            b.write(len & 0xFF);
        } else {
            b.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) b.write((int) ((len >> (i * 8)) & 0xFF));
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        b.write(mask);
        for (int i = 0; i < payload.length; i++) b.write(payload[i] ^ mask[i & 3]);
        out.write(b.toByteArray());
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}

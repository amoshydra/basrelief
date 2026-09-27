package com.amoshydra.basrelief;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal dependency-free client for the ComfyUI HTTP API
 * (upload, /object_info, /prompt, /history, /view).
 */
public final class ComfyApi {

    private final String base;

    public ComfyApi(String baseUrl) {
        String b = baseUrl.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(120000);
        return c;
    }

    /** GET /object_info — the JSON map of every installed node. */
    public String getObjectInfo() throws IOException {
        return readString(open("/object_info", "GET"));
    }

    /** POST /upload/image (multipart) — returns JSON {name, subfolder}. */
    public String uploadImage(File file, String fileName) throws IOException {
        HttpURLConnection c = open("/upload/image", "POST");
        String boundary = "----depth" + System.nanoTime();
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        OutputStream os = c.getOutputStream();
        writeField(os, boundary, "overwrite", "true");
        writeFileField(os, boundary, "image", fileName, file);
        os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();
        return readString(c);
    }

    /** POST /prompt — body {prompt, client_id}; returns JSON with prompt_id. */
    public String submitPrompt(String promptJson, String clientId) throws IOException {
        HttpURLConnection c = open("/prompt", "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        String body = "{\"prompt\":" + promptJson + ",\"client_id\":\"" + clientId + "\"}";
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();
        return readString(c);
    }

    /** GET /history/{id} — JSON map; empty until the run completes. */
    public String getHistory(String promptId) throws IOException {
        HttpURLConnection c = open("/history/" + promptId, "GET");
        c.setReadTimeout(5000);
        return readString(c);
    }

    /** GET /view?filename=...&subfolder=...&type=... — the generated image bytes. */
    public byte[] view(String filename, String subfolder, String type) throws IOException {
        String path = "/view?filename=" + enc(filename)
                + "&subfolder=" + enc(subfolder)
                + "&type=" + enc(type);
        HttpURLConnection c = open(path, "GET");
        return readBytes(c);
    }

    // ------------------------------------------------------------------ helpers

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static void writeField(OutputStream os, String boundary, String name, String value)
            throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        os.write(value.getBytes(StandardCharsets.UTF_8));
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFileField(OutputStream os, String boundary, String name,
                                       String fileName, File file) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        FileInputStream fis = new FileInputStream(file);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            fis.close();
        }
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(HttpURLConnection c) throws IOException {
        return new String(readBytes(c), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new IOException("no response from server (HTTP " + code + ")");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            byte[] bytes = out.toByteArray();
            if (code >= 400) {
                throw new IOException("ComfyUI HTTP " + code + ": "
                        + new String(bytes, StandardCharsets.UTF_8));
            }
            return bytes;
        } finally {
            in.close();
            c.disconnect();
        }
    }
}

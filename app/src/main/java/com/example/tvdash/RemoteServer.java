package com.example.tvdash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server (no libraries) so a phone browser on the same Wi-Fi can control the TV.
 * GET /            -> the remote web page
 * GET|POST /api/.. -> needs ?pin=XXXX (the PIN shown on the TV)
 */
final class RemoteServer {

    interface Host {
        void onKey(String key);
        void onAppsChanged();
        void onSettingsChanged();
    }

    private static final int MAX_UPLOAD = 12 * 1024 * 1024;

    private final Context ctx;
    private final Host host;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;

    RemoteServer(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
    }

    void start(int port) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        pool.execute(new Runnable() {
                            @Override public void run() { handle(s); }
                        });
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "remote-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String request = readLine(in);
            if (request == null || request.isEmpty()) return;

            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    try { contentLength = Integer.parseInt(line.substring(c + 1).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }

            String[] parts = request.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            String path = target;
            Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        q.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                              URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                    }
                }
            }

            OutputStream out = s.getOutputStream();
            if (path.equals("/") || path.equals("/index.html")) {
                send(out, 200, "text/html", readAsset("remote.html"));
            } else if (path.startsWith("/api/")) {
                if (!AppStore.pin(ctx).equals(q.get("pin"))) {
                    send(out, 401, "application/json", "{\"error\":\"bad pin\"}");
                } else if (path.equals("/api/upload")) {
                    upload(in, out, method, contentLength);
                } else if (path.equals("/api/wall")) {
                    serveWall(out, q);
                } else {
                    send(out, 200, "application/json", api(path, q));
                }
            } else {
                send(out, 404, "text/plain", "not found");
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    // ---- JSON API ----

    private String api(String path, Map<String, String> q) {
        switch (path) {
            case "/api/ping":
                return "{\"ok\":true}";
            case "/api/apps": {
                List<AppStore.Entry> list = AppStore.load(ctx, true);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) {
                    AppStore.Entry e = list.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"label\":").append(json(e.label))
                      .append(",\"pkg\":").append(json(e.pkg))
                      .append(",\"hidden\":").append(e.hidden).append('}');
                }
                return sb.append(']').toString();
            }
            case "/api/launch": {
                boolean ok = AppStore.launch(ctx, String.valueOf(q.get("pkg")));
                return "{\"ok\":" + ok + "}";
            }
            case "/api/key":
                host.onKey(String.valueOf(q.get("k")));
                return "{\"ok\":true}";
            case "/api/hide":
                AppStore.setHidden(ctx, String.valueOf(q.get("pkg")), "1".equals(q.get("hide")));
                host.onAppsChanged();
                return "{\"ok\":true}";
            case "/api/settings":
                return settingsJson();
            case "/api/set": {
                boolean ok = AppStore.setSetting(ctx, q.get("key"), q.get("value"));
                if (ok) host.onSettingsChanged();
                return "{\"ok\":" + ok + "}";
            }
            case "/api/delwall": {
                AppStore.deleteWall(ctx, q.get("id"));
                host.onSettingsChanged();
                return "{\"ok\":true}";
            }
            default:
                return "{\"error\":\"unknown\"}";
        }
    }

    private String settingsJson() {
        AppStore.Settings s = AppStore.settings(ctx);
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"theme\":").append(json(s.theme))
          .append(",\"cols\":").append(s.cols)
          .append(",\"size\":").append(s.size)
          .append(",\"clock24\":").append(s.clock24)
          .append(",\"showClock\":").append(s.showClock)
          .append(",\"title\":").append(json(s.title))
          .append(",\"wall\":").append(json(s.wall))
          .append(",\"dim\":").append(s.dim)
          .append(",\"slide\":").append(s.slide)
          .append(",\"cTop\":").append(json(s.cTop))
          .append(",\"cBottom\":").append(json(s.cBottom))
          .append(",\"cAccent\":").append(json(s.cAccent))
          .append(",\"cText\":").append(json(s.cText))
          .append(",\"walls\":[");
        List<String> walls = AppStore.walls(ctx);
        for (int i = 0; i < walls.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(json(walls.get(i)));
        }
        sb.append("],\"themes\":[");
        for (int i = 0; i < Themes.ALL.length; i++) {
            Themes.Theme t = Themes.ALL[i];
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(json(t.id))
              .append(",\"name\":").append(json(t.name))
              .append(",\"top\":").append(json(Themes.hex(t.top)))
              .append(",\"bottom\":").append(json(Themes.hex(t.bottom)))
              .append(",\"accent\":").append(json(Themes.hex(t.accent)))
              .append(",\"text\":").append(json(Themes.hex(t.text)))
              .append('}');
        }
        return sb.append("]}").toString();
    }

    // ---- pictures ----

    private void upload(InputStream in, OutputStream out, String method, int len) throws IOException {
        if (!"POST".equals(method) || len <= 0) {
            send(out, 400, "application/json", "{\"ok\":false,\"error\":\"bad request\"}");
            return;
        }
        if (len > MAX_UPLOAD) {
            send(out, 413, "application/json", "{\"ok\":false,\"error\":\"too big\"}");
            return;
        }
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(data, off, len - off);
            if (n < 0) break;
            off += n;
        }
        if (off < len) {
            send(out, 400, "application/json", "{\"ok\":false,\"error\":\"incomplete\"}");
            return;
        }
        if (!AppStore.canAddWall(ctx)) {
            send(out, 400, "application/json", "{\"ok\":false,\"error\":\"too many pictures, delete some\"}");
            return;
        }
        String id = saveWall(data);
        if (id == null) {
            send(out, 400, "application/json", "{\"ok\":false,\"error\":\"not a valid image\"}");
            return;
        }
        host.onSettingsChanged();
        send(out, 200, "application/json", "{\"ok\":true,\"id\":" + json(id) + "}");
    }

    /** Decodes the picture, shrinks it if huge, and stores it as JPEG. Returns the new id or null. */
    private String saveWall(byte[] data) {
        Bitmap bmp = null;
        FileOutputStream fo = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / sample > 2400 || o.outHeight / sample > 2400) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            bmp = BitmapFactory.decodeByteArray(data, 0, data.length, d);
            if (bmp == null) return null;
            String id = AppStore.newWallId(ctx);
            fo = new FileOutputStream(AppStore.wallFile(ctx, id));
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, fo);
            return id;
        } catch (IOException e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            try { if (fo != null) fo.close(); } catch (IOException ignored) {}
            if (bmp != null) bmp.recycle();
        }
    }

    private void serveWall(OutputStream out, Map<String, String> q) throws IOException {
        File f = AppStore.wallFile(ctx, q.get("id"));
        if (f == null || !f.exists()) {
            send(out, 404, "text/plain", "not found");
            return;
        }
        byte[] bytes;
        if ("1".equals(q.get("thumb"))) {
            bytes = thumb(f);
        } else {
            bytes = readFile(f);
        }
        if (bytes == null) {
            send(out, 404, "text/plain", "not found");
            return;
        }
        sendBytes(out, 200, "image/jpeg", bytes, "public, max-age=86400");
    }

    private byte[] thumb(File f) {
        Bitmap bmp = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 400) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(f.getPath(), d);
            if (bmp == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, bo);
            return bo.toByteArray();
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private static byte[] readFile(File f) throws IOException {
        InputStream is = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            is.close();
        }
    }

    // ---- helpers ----

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private String readAsset(String name) throws IOException {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    private void send(OutputStream out, int code, String type, String body) throws IOException {
        sendBytes(out, code, type + "; charset=utf-8", body.getBytes("UTF-8"), "no-store");
    }

    private void sendBytes(OutputStream out, int code, String type, byte[] b, String cache) throws IOException {
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 400: status = "Bad Request"; break;
            case 401: status = "Unauthorized"; break;
            case 413: status = "Payload Too Large"; break;
            default: status = "Not Found";
        }
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: " + cache + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
    }
}

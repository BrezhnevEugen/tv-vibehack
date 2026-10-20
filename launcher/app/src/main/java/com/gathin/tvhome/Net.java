package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** Small blocking HTTP GETs for background threads (no libraries on purpose: the APK stays small). */
final class Net {

    private Net() {
    }

    static byte[] get(String url, Map<String, String> headers, int maxBytes) throws IOException {
        URL u = new URL(url);
        String ip = poisoned(u) ? dohResolve(u.getHost()) : null;
        if (ip != null) return rawGet(ip, u, headers, maxBytes);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) throw new IOException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Some DNS answers for TMDB (Google DNS, ISP DNS from Russia) are 127.0.0.1. If the system resolves a host to a
     * loopback address, ask Cloudflare's DNS-over-HTTPS instead and connect to that IP, keeping the real host name
     * for TLS (SNI and certificate check), so the request is exactly what it would have been with honest DNS.
     */
    /** Does the system DNS answer a loopback address for this https host (a DNS block)? */
    private static boolean poisoned(URL u) {
        if (!"https".equals(u.getProtocol())) return false;
        try {
            return java.net.InetAddress.getByName(u.getHost()).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * HTTP/1.0 GET over TLS to a given IP, with the real host name for SNI, the certificate check and the Host
     * header. Done by hand because HttpsURLConnection replaces the SNI with the IP when the URL holds an IP.
     */
    private static byte[] rawGet(String ip, URL u, Map<String, String> headers, int maxBytes) throws IOException {
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 443;
        javax.net.ssl.SSLSocketFactory f = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
        java.net.Socket plain = new java.net.Socket();
        plain.connect(new java.net.InetSocketAddress(ip, port), 8000);
        plain.setSoTimeout(25000);
        try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) f.createSocket(plain, host, port, true)) {
            javax.net.ssl.SSLParameters p = s.getSSLParameters();
            p.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName(host)));
            s.setSSLParameters(p);
            s.startHandshake();
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
                throw new IOException("certificate does not match " + host);
            }
            StringBuilder req = new StringBuilder("GET ").append(u.getFile().isEmpty() ? "/" : u.getFile())
                    .append(" HTTP/1.0\r\nHost: ").append(host).append("\r\nConnection: close\r\nUser-Agent: TvHome\r\n");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            req.append("\r\n");
            s.getOutputStream().write(req.toString().getBytes("UTF-8"));
            s.getOutputStream().flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            InputStream in = s.getInputStream();
            while ((n = in.read(buf)) > 0) {
                all.write(buf, 0, n);
                if (all.size() > maxBytes + 65536) throw new IOException("response too large");
            }
            byte[] r = all.toByteArray();
            int split = -1;
            for (int i = 0; i + 3 < r.length; i++) {
                if (r[i] == '\r' && r[i + 1] == '\n' && r[i + 2] == '\r' && r[i + 3] == '\n') {
                    split = i;
                    break;
                }
            }
            if (split < 0) throw new IOException("bad HTTP response");
            String head = new String(r, 0, split, "ISO-8859-1");
            String status = head.split("\r\n", 2)[0];
            if (!status.matches("HTTP/1\\.[01] 200.*")) throw new IOException(status);
            return java.util.Arrays.copyOfRange(r, split + 4, r.length);
        }
    }

    private static final Map<String, String> DOH_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static String dohResolve(String host) {
        String cached = DOH_CACHE.get(host);
        if (cached != null) return cached;
        for (String doh : new String[]{"https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query",
                "https://cloudflare-dns.com/dns-query", "https://dns.quad9.net:5053/dns-query"}) try {
            HttpURLConnection c = (HttpURLConnection) new URL(doh + "?type=A&name="
                    + java.net.URLEncoder.encode(host, "UTF-8")).openConnection();
            c.setRequestProperty("accept", "application/dns-json");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
                org.json.JSONArray ans = new org.json.JSONObject(out.toString("UTF-8")).optJSONArray("Answer");
                if (ans == null) continue;
                for (int i = 0; i < ans.length(); i++) {
                    org.json.JSONObject a = ans.getJSONObject(i);
                    String d = a.optString("data");
                    if (a.optInt("type") == 1 && !d.startsWith("127.")) {
                        DOH_CACHE.put(host, d);
                        return d;
                    }
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            android.util.Log.w("TvHomeNet", "DoH " + doh + " failed: " + e);
        }
        return null;
    }

    static String text(String url, Map<String, String> headers) throws IOException {
        return new String(get(url, headers, 1 << 20), "UTF-8");
    }

    /** Decodes an image, scaled down so its width is at most maxWidth (memory: these TVs have 1.5 GB). */
    static Bitmap image(String url, int maxWidth) throws IOException {
        byte[] data = get(url, null, 4 << 20);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= maxWidth) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) throw new IOException("not an image");
        return b;
    }
}

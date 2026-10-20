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
        HttpURLConnection c = open(new URL(url));
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
    private static HttpURLConnection open(URL u) throws IOException {
        if (!"https".equals(u.getProtocol())) return (HttpURLConnection) u.openConnection();
        final String host = u.getHost();
        boolean loop;
        try {
            loop = java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            loop = true;
        }
        if (!loop) return (HttpURLConnection) u.openConnection();
        String ip = dohResolve(host);
        if (ip == null) return (HttpURLConnection) u.openConnection();
        URL byIp = new URL("https", ip, u.getPort(), u.getFile());
        javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection) byIp.openConnection();
        c.setRequestProperty("Host", host);
        c.setSSLSocketFactory(new SniFactory((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault(), host));
        c.setHostnameVerifier((h, session) ->
                javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session));
        return c;
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

    /** Sets the TLS server name (SNI) on every socket, since we connect by IP. */
    private static final class SniFactory extends javax.net.ssl.SSLSocketFactory {
        private final javax.net.ssl.SSLSocketFactory base;
        private final String host;

        SniFactory(javax.net.ssl.SSLSocketFactory base, String host) {
            this.base = base;
            this.host = host;
        }

        private java.net.Socket sni(java.net.Socket s) {
            if (s instanceof javax.net.ssl.SSLSocket) {
                javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket) s;
                javax.net.ssl.SSLParameters p = ss.getSSLParameters();
                p.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName(host)));
                ss.setSSLParameters(p);
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
        @Override public java.net.Socket createSocket(java.net.Socket s, String h, int port, boolean auto) throws IOException { return sni(base.createSocket(s, host, port, auto)); }
        @Override public java.net.Socket createSocket(String h, int port) throws IOException { return sni(base.createSocket(h, port)); }
        @Override public java.net.Socket createSocket(String h, int port, java.net.InetAddress la, int lp) throws IOException { return sni(base.createSocket(h, port, la, lp)); }
        @Override public java.net.Socket createSocket(java.net.InetAddress a, int port) throws IOException { return sni(base.createSocket(a, port)); }
        @Override public java.net.Socket createSocket(java.net.InetAddress a, int port, java.net.InetAddress la, int lp) throws IOException { return sni(base.createSocket(a, port, la, lp)); }
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

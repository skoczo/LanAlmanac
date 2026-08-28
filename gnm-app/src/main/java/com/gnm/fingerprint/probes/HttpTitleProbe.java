package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.net.URL;
import java.net.URI;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.security.SecureRandom;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class HttpTitleProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(HttpTitleProbe.class);

    @Override
    public int getTimeoutMs() {
        return 3500;
    }

    @Override
    public int getPriority() {
        return 90;
    }

    @Override
    public void execute(ProbeContext context) {
        if (context.getResolvedHostname() != null) return;
        List<Integer> httpPorts = List.of(80, 8080, 8000, 8123, 443, 8443, 8006);
        for (int port : httpPorts) {
            if (!context.getOpenPorts().isEmpty() && !context.getOpenPorts().contains(port)) continue;
            boolean https = port == 443 || port == 8443 || port == 8006;
            try {
                String protocol = https ? "https" : "http";
                URL url = new URI(protocol + "://" + context.getIpAddress() + ":" + port + "/").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                if (https && conn instanceof HttpsURLConnection) {
                    HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                    TrustManager[] trustAllCerts = new TrustManager[] {
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return null; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                    };
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, trustAllCerts, new SecureRandom());
                    httpsConn.setSSLSocketFactory(sc.getSocketFactory());
                    httpsConn.setHostnameVerifier((hostname, session) -> true);
                }
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "GNM-Scanner/1.0");
                conn.connect();
                int code = conn.getResponseCode();
                if (code == 200 || code == 401 || code == 403) {
                    StringBuilder html = new StringBuilder();
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String inputLine;
                        while ((inputLine = in.readLine()) != null) {
                            html.append(inputLine);
                            if (html.length() > 8192) break;
                        }
                    } catch (Exception e) {
                        try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                            String inputLine;
                            while ((inputLine = err.readLine()) != null) {
                                html.append(inputLine);
                                if (html.length() > 8192) break;
                            }
                        } catch (Exception ignored) {}
                    }
                    Matcher m = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html.toString());
                    if (m.find()) {
                        String title = m.group(1).trim();
                        if (!title.isEmpty() && !title.equalsIgnoreCase("NetAlmanac") && !title.toLowerCase().contains("network manager")) {
                            context.setResolvedHostname(title);
                            return;
                        }
                    }
                }
            } catch (Exception e) {}
        }
    }
}

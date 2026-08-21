package com.oastest.automation.service;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Builds {@link HttpClient} instances that ignore TLS certificate validation.
 *
 * <p>This is intentional: the gateway targets under test frequently use internal/self-signed
 * certificates, and the tool is only ever pointed at systems the operator explicitly chooses to
 * test. Do NOT reuse this outside that context.</p>
 */
public final class InsecureHttp {

    private InsecureHttp() {
    }

    private static final TrustManager[] TRUST_ALL = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
    };

    public static HttpClient client(Duration connectTimeout) {
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, TRUST_ALL, new SecureRandom());
            // Disable hostname verification for self-signed internal endpoints.
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            return HttpClient.newBuilder()
                    .sslContext(ssl)
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build insecure HTTP client", e);
        }
    }
}

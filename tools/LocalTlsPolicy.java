import javax.net.ssl.*;
/** Shared explicit server policy; no global security-provider settings are weakened. */
public final class LocalTlsPolicy {
    static String[] protocols() { return new String[]{"TLSv1.2", "TLSv1.3"}; }
    static void configure(SSLServerSocket listener) { listener.setEnabledProtocols(protocols()); }
    static SSLParameters parameters(SSLContext context) {
        SSLParameters params = context.getDefaultSSLParameters(); params.setProtocols(protocols()); return params;
    }
    private LocalTlsPolicy() {}
}

import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Owned local HTTPS fixture for Lab 15. Requires a PKCS12 keystore and env-only test key. */
public final class LocalLab15MockServer {
    private static final Map<String, Long> NONCES = new HashMap<>();
    private static final int MAX_NONCES = 50_000;
    enum Verification {
        ACCEPTED(200, "accepted"), SIGNATURE(401, "Signature"), CLOCK(401, "Clock"),
        REPLAY(409, "Replay"), CAPACITY(503, "ReplayCacheFull");
        final int status; final String message;
        Verification(int status, String message) { this.status = status; this.message = message; }
    }
    public static void main(String[] args) throws Exception {
        String keystorePath = System.getenv("PHONOTEKA_MOCK_KEYSTORE");
        String keystorePassword = System.getenv("PHONOTEKA_MOCK_KEYSTORE_PASSWORD");
        String hmacKey = System.getenv("PHONOTEKA_MOCK_HMAC_KEY");
        if (keystorePath == null || keystorePassword == null || hmacKey == null) {
            throw new IllegalStateException("Set PHONOTEKA_MOCK_KEYSTORE, PHONOTEKA_MOCK_KEYSTORE_PASSWORD and PHONOTEKA_MOCK_HMAC_KEY in the local shell.");
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(keystorePath))) { store.load(in, keystorePassword.toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, keystorePassword.toCharArray());
        SSLContext tls = SSLContext.getInstance("TLSv1.3");
        tls.init(kmf.getKeyManagers(), null, new SecureRandom());
        startFixture(tls, hmacKey, 8443);
        System.out.println("Owned HTTPS lab server listening on https://localhost:8443/api/test");
    }
    /** Package-private own-loopback seam; port0 selects an ephemeral test port. */
    static HttpsServer startFixture(SSLContext tls, String hmacKey, int port) throws IOException {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls) {
            @Override public void configure(HttpsParameters parameters) {
                parameters.setSSLParameters(LocalTlsPolicy.parameters(tls));
            }
        });
        server.createContext("/api/test", exchange -> {
            byte[] body;
            try { body = readBody(exchange.getRequestBody()); }
            catch (IOException tooLarge) { exchange.sendResponseHeaders(413, -1); exchange.close(); return; }
            String timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");
            String nonce = exchange.getRequestHeaders().getFirst("X-Nonce");
            String signature = exchange.getRequestHeaders().getFirst("X-Signature");
            String target = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null) target += "?" + query;
            Verification result = verifyResult(hmacKey, exchange.getRequestMethod(), target, body,
                timestamp, nonce, signature, System.currentTimeMillis() / 1000);
            int status = result.status;
            // Never log nonce, signature, key, body or query/ticket values.
            System.out.println(exchange.getRequestMethod() + " -> " + result.message);
            byte[] response = result.message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        return server;
    }
    private static byte[] readBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 65536) throw new IOException("Local request body exceeds limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static boolean verify(String key, String method, String path, byte[] body, String timestamp, String nonce, String signature) {
        return verify(key, method, path, body, timestamp, nonce, signature, System.currentTimeMillis() / 1000);
    }

    // Boolean seam retained for the WSS handshake and existing standalone regressions.
    static boolean verify(String key, String method, String path, byte[] body, String timestamp,
                          String nonce, String signature, long now) {
        return verifyResult(key, method, path, body, timestamp, nonce, signature, now) == Verification.ACCEPTED;
    }
    static Verification verifyResult(String key, String method, String path, byte[] body, String timestamp,
                          String nonce, String signature, long now) {
        try {
            if (timestamp == null || nonce == null || signature == null ||
                !nonce.matches("[A-Za-z0-9_-]{16,64}") || !signature.matches("[0-9a-fA-F]{64}")) return Verification.SIGNATURE;
            long sent = Long.parseLong(timestamp);
            long offset = Math.subtractExact(now, sent);
            if (offset < -30 || offset > 30) return Verification.CLOCK;
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + sent + "\n" + nonce + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) return Verification.SIGNATURE;
            long validUntil = sent > Long.MAX_VALUE - 30 ? Long.MAX_VALUE : sent + 30;
            return consumeNonce(nonce, now, validUntil);
        } catch (Exception ignored) { return Verification.SIGNATURE; }
    }
    private static synchronized Verification consumeNonce(String nonce, long now, long validUntil) {
        NONCES.entrySet().removeIf(entry -> now > entry.getValue());
        if (NONCES.containsKey(nonce)) return Verification.REPLAY;
        if (NONCES.size() >= MAX_NONCES) return Verification.CAPACITY; // Fail closed without evicting live nonces.
        NONCES.put(nonce, validUntil);
        return Verification.ACCEPTED;
    }
}

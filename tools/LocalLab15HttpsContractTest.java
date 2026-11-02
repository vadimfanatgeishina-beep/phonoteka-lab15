import com.sun.net.httpserver.HttpsServer;
import javax.net.ssl.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/** Real owned HTTPS request/response contract; no global CA/proxy changes and no trust-all. */
public final class LocalLab15HttpsContractTest {
    public static void main(String[] args) throws Exception {
        String storePath = System.getenv("PHONOTEKA_MOCK_KEYSTORE");
        char[] password = System.getenv("PHONOTEKA_MOCK_KEYSTORE_PASSWORD").toCharArray();
        String key = System.getenv("PHONOTEKA_MOCK_HMAC_KEY");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(Path.of(storePath))) { store.load(in, password); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); kmf.init(store, password);
        Arrays.fill(password, '\0');
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
        Enumeration<String> aliases = store.aliases(); int count = 0;
        while (aliases.hasMoreElements()) {
            java.security.cert.Certificate certificate = store.getCertificate(aliases.nextElement());
            if (certificate != null) trust.setCertificateEntry("owned-" + count++, certificate);
        }
        if (count == 0) throw new AssertionError("No owned fixture certificate");
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(trust);
        SSLContext serverTls = SSLContext.getInstance("TLS"); serverTls.init(kmf.getKeyManagers(), null, new SecureRandom());
        SSLContext clientTls = SSLContext.getInstance("TLS"); clientTls.init(null, tmf.getTrustManagers(), new SecureRandom());
        HttpsServer server = LocalLab15MockServer.startFixture(serverTls, key, 0);
        try {
            String target = "/api/test?item=owned";
            URI uri = URI.create("https://localhost:" + server.getAddress().getPort() + target);
            HttpClient client = HttpClient.newBuilder().sslContext(clientTls).connectTimeout(Duration.ofSeconds(3)).build();
            long now = System.currentTimeMillis() / 1000;
            String nonce = UUID.randomUUID().toString(); byte[] body = "owned-body".getBytes(StandardCharsets.UTF_8);
            String signature = sign(key, target, now, nonce, body);
            expect(client, uri, now, nonce, signature, body, 200, "accepted");
            expect(client, uri, now, nonce, signature, body, 409, "Replay");
            String fresh = UUID.randomUUID().toString(); String freshSignature = sign(key, target, now, fresh, body);
            expect(client, uri, now, fresh, freshSignature, "changed-body".getBytes(StandardCharsets.UTF_8), 401, "Signature");
            expect(client, uri, now, fresh, freshSignature, body, 200, "accepted"); // Invalid signature did not consume nonce.
            String expiredNonce = UUID.randomUUID().toString(); long expired = now - 300;
            expect(client, uri, expired, expiredNonce, sign(key, target, expired, expiredNonce, body), body, 401, "Clock");
            System.out.println("Owned HTTPS contract: accepted/replay409/tamper401/nonce-not-consumed/clock401 PASS");
        } finally { server.stop(0); }
    }
    private static void expect(HttpClient client, URI uri, long sent, String nonce, String signature, byte[] body, int code, String reason) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
            .header("X-Timestamp", Long.toString(sent)).header("X-Nonce", nonce).header("X-Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != code || !reason.equals(response.body())) throw new AssertionError("HTTPS status/body mismatch");
    }
    private static String sign(String key, String path, long sent, String nonce, byte[] body) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String canonical = "POST\n" + path + "\n" + sent + "\n" + nonce + "\n" + hash;
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
}

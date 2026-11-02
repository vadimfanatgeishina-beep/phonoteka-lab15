import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** No listener, keystore, credentials or external network is used by this regression test. */
public final class LocalLab15MockServerTest {
    public static void main(String[] args) throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String fixtureKey = HexFormat.of().formatHex(random);
        String nonce = "future_nonce_java_0001";
        byte[] body = new byte[0];
        String signature = sign(fixtureKey, "/api/test?item=one", 130, nonce, body);
        expect(true, verify(fixtureKey, nonce, signature, 100), "first future request");
        expect(false, verify(fixtureKey, nonce, signature, 131), "replay after 31 seconds");
        expect(false, verify(fixtureKey, nonce, signature, 160), "replay at inclusive expiry");
        expect(false, verify(fixtureKey, nonce, signature, 161), "expired request");
        String otherNonce = "future_nonce_java_0002";
        String otherSignature = sign(fixtureKey, "/api/test?item=one", 130, otherNonce, body);
        expect(true, verify(fixtureKey, otherNonce, otherSignature, 160), "fresh nonce at boundary");
        String freshNonce = "future_nonce_java_0003";
        String freshSignature = sign(fixtureKey, "/api/test?item=one", 130, freshNonce, body);
        expect(false, LocalLab15MockServer.verify(fixtureKey, "GET", "/api/test?item=two", body,
            "130", freshNonce, freshSignature, 130), "changed query/signature");
        expect(true, verify(fixtureKey, freshNonce, freshSignature, 130), "invalid signature did not consume nonce");
        expect(false, LocalLab15MockServer.verify(fixtureKey, "GET", "/api/test?item=one", body,
            Long.toString(Long.MIN_VALUE), nonce, signature, 0), "timestamp overflow");
        String contractNonce = "contract_nonce_java_001";
        String contractSig = sign(fixtureKey, "/api/test?item=one", 200, contractNonce, body);
        expectResult(LocalLab15MockServer.Verification.ACCEPTED,
            LocalLab15MockServer.verifyResult(fixtureKey, "GET", "/api/test?item=one", body, "200", contractNonce, contractSig, 200));
        expectResult(LocalLab15MockServer.Verification.REPLAY,
            LocalLab15MockServer.verifyResult(fixtureKey, "GET", "/api/test?item=one", body, "200", contractNonce, contractSig, 201));
        if (LocalLab15MockServer.Verification.REPLAY.status != 409 || !LocalLab15MockServer.Verification.REPLAY.message.equals("Replay")) throw new AssertionError("Replay HTTP contract");
        String changedNonce = "changed_nonce_java_0001";
        String changedSig = sign(fixtureKey, "/api/test?item=one", 200, changedNonce, body);
        expectResult(LocalLab15MockServer.Verification.SIGNATURE,
            LocalLab15MockServer.verifyResult(fixtureKey, "GET", "/api/test?item=one", "changed".getBytes(StandardCharsets.UTF_8), "200", changedNonce, changedSig, 200));
        expectResult(LocalLab15MockServer.Verification.ACCEPTED,
            LocalLab15MockServer.verifyResult(fixtureKey, "GET", "/api/test?item=one", body, "200", changedNonce, changedSig, 200));
        expectResult(LocalLab15MockServer.Verification.CLOCK,
            LocalLab15MockServer.verifyResult(fixtureKey, "GET", "/api/test?item=one", body, "200", changedNonce, changedSig, 500));
        if (LocalLab15MockServer.Verification.SIGNATURE.status != 401 || !LocalLab15MockServer.Verification.SIGNATURE.message.equals("Signature")) throw new AssertionError("Signature HTTP contract");
        System.out.println("Local Java nonce/result/status regression: OK");
    }
    private static void expectResult(LocalLab15MockServer.Verification expected, LocalLab15MockServer.Verification actual) {
        if (expected != actual) throw new AssertionError("Typed verification result mismatch");
    }
    private static boolean verify(String key, String nonce, String signature, long now) {
        return LocalLab15MockServer.verify(key, "GET", "/api/test?item=one", new byte[0],
            "130", nonce, signature, now);
    }
    private static String sign(String key, String target, long sent, String nonce, byte[] body) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String canonical = "GET\n" + target + "\n" + sent + "\n" + nonce + "\n" + hash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
    private static void expect(boolean expected, boolean actual, String label) {
        if (expected != actual) throw new AssertionError(label);
    }
}

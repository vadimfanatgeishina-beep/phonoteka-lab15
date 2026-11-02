import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Own ephemeral loopback server. Raw old-version ClientHello proves SERVER refusal,
 * unlike a JSSE client which may locally disable TLS1.0/1.1 before sending a packet.
 * Requires the owner's fixture PKCS12 via environment; never prints private material.
 */
public final class LocalLab15TlsDowngradeTest {
    public static void main(String[] args) throws Exception {
        String path = System.getenv("PHONOTEKA_MOCK_KEYSTORE");
        String password = System.getenv("PHONOTEKA_MOCK_KEYSTORE_PASSWORD");
        if (path == null || password == null) throw new IllegalStateException("Configure owned local fixture PKCS12 environment");
        char[] pass = password.toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(path))) { store.load(in, pass); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); kmf.init(store, pass);
        Arrays.fill(pass, '\0');
        // Trust only public certs from this owned store in this one test's private SSLContext.
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
        Enumeration<String> aliases = store.aliases(); int count = 0;
        while (aliases.hasMoreElements()) { java.security.cert.Certificate certificate = store.getCertificate(aliases.nextElement());
            if (certificate != null) trust.setCertificateEntry("owned-" + count++, certificate); }
        if (count == 0) throw new IllegalStateException("Owned certificate absent");
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        positive(context, "TLSv1.2"); positive(context, "TLSv1.3");
        rejectRawHello(context, 1, "TLSv1.0"); rejectRawHello(context, 2, "TLSv1.1");
    }
    private static SSLServerSocket listener(SSLContext context) throws IOException {
        SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        LocalTlsPolicy.configure(socket); socket.setSoTimeout(4000); return socket;
    }
    private static void positive(SSLContext context, String protocol) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (SSLServerSocket listener = listener(context)) {
            Future<String> server = executor.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) { socket.setSoTimeout(4000); socket.startHandshake();
                    if (socket.getInputStream().read() != 42) throw new IOException("Fixture exchange failed");
                    socket.getOutputStream().write(42); return socket.getSession().getProtocol(); }
            });
            try (SSLSocket client = (SSLSocket) context.getSocketFactory().createSocket("localhost", listener.getLocalPort())) {
                client.setEnabledProtocols(new String[]{protocol}); client.setSoTimeout(4000);
                SSLParameters parameters = client.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); client.setSSLParameters(parameters);
                client.startHandshake(); client.getOutputStream().write(42);
                if (client.getInputStream().read() != 42 || !protocol.equals(client.getSession().getProtocol())) throw new AssertionError("Positive fixture handshake failed");
            }
            if (!protocol.equals(server.get(5, TimeUnit.SECONDS))) throw new AssertionError("Wrong server protocol");
            System.out.println(protocol + ": owned client/server handshake accepted");
        } finally { executor.shutdownNow(); }
    }
    private static void rejectRawHello(SSLContext context, int minor, String label) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (SSLServerSocket listener = listener(context)) {
            Future<Boolean> server = executor.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(4000);
                    try { socket.startHandshake(); return false; } catch (SSLHandshakeException expected) { return true; }
                }
            });
            try (Socket client = new Socket(InetAddress.getByName("127.0.0.1"), listener.getLocalPort())) {
                client.setSoTimeout(4000); client.getOutputStream().write(clientHello(minor)); client.getOutputStream().flush();
                byte[] header = exact(client.getInputStream(), 5);
                int length = (header[3] & 255) * 256 + (header[4] & 255);
                if (length > 4096) throw new AssertionError("Unexpected server record size");
                byte[] alert = exact(client.getInputStream(), length);
                if ((header[0] & 255) != 21 || alert.length != 2 || alert[0] != 2 || (alert[1] & 255) != 70)
                    throw new AssertionError("Expected SERVER fatal protocol_version alert; EOF/local refusal is NOT a pass");
            }
            if (!server.get(5, TimeUnit.SECONDS)) throw new AssertionError("Old protocol unexpectedly accepted");
            System.out.println(label + ": SERVER fatal protocol_version alert observed on owned loopback");
        } finally { executor.shutdownNow(); }
    }
    private static byte[] clientHello(int minor) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(); body.write(3); body.write(minor);
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random); body.write(random);
        body.write(0); // session ID length
        body.write(0); body.write(2); body.write(0); body.write(0x2f); // one legacy cipher suite
        body.write(1); body.write(0); // null compression only
        byte[] payload = body.toByteArray();
        ByteArrayOutputStream handshake = new ByteArrayOutputStream(); handshake.write(1);
        handshake.write(0); handshake.write(0); handshake.write(payload.length); handshake.write(payload);
        byte[] message = handshake.toByteArray();
        ByteArrayOutputStream record = new ByteArrayOutputStream(); record.write(22); record.write(3); record.write(minor);
        record.write(message.length >> 8); record.write(message.length & 255); record.write(message); return record.toByteArray();
    }
    private static byte[] exact(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new EOFException("Server alert incomplete, no pass"); return bytes;
    }
}

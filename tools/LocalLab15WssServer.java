import javax.net.ssl.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Minimal RFC6455 local lab fixture, not a general-purpose WebSocket server. */
public final class LocalLab15WssServer {
    private static final Map<String, Long> NONCES = new HashMap<>();
    public static void main(String[] args) throws Exception {
        String path = System.getenv("PHONOTEKA_MOCK_KEYSTORE");
        String password = System.getenv("PHONOTEKA_MOCK_KEYSTORE_PASSWORD");
        String key = System.getenv("PHONOTEKA_MOCK_HMAC_KEY");
        if (path == null || password == null || key == null) throw new IllegalStateException("Configure owned local fixture environment");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(path))) { store.load(in, password.toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password.toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(kmf.getKeyManagers(), null, new SecureRandom());
        SSLServerSocket listener = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(8444, 16, InetAddress.getByName("127.0.0.1"));
        LocalTlsPolicy.configure(listener);
        ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));
        System.out.println("Owned local WSS fixture on localhost:8444/ws");
        try {
            while (true) {
                SSLSocket socket = (SSLSocket) listener.accept();
                try {
                    workers.execute(() -> { try (socket) { handle(socket, key); } catch (Exception ignored) { System.out.println("Local WSS connection rejected"); } });
                } catch (RejectedExecutionException full) { socket.close(); }
            }
        } finally { listener.close(); workers.shutdownNow(); }
    }
    private static void handle(SSLSocket socket, String key) throws Exception {
        socket.setSoTimeout(10000);
        InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int b; String text = "";
        while ((b = in.read()) != -1) {
            headers.write(b); if (headers.size() > 8192) throw new IOException("Headers too large");
            if (b == '\n') { text = headers.toString(StandardCharsets.US_ASCII); if (text.endsWith("\r\n\r\n")) break; }
        }
        String[] lines = text.split("\r\n");
        if (lines.length < 2 || !lines[0].equals("GET /ws HTTP/1.1")) throw new IOException("Invalid local target");
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i < lines.length; i++) { int colon = lines[i].indexOf(':'); if (colon > 0) values.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim()); }
        String channel = values.get("x-lab-channel");
        String websocketKey = values.get("sec-websocket-key");
        if (channel == null || !channel.matches("/lab15/u/[a-f0-9]{16}/[a-f0-9-]{36}") || websocketKey == null ||
            !"websocket".equalsIgnoreCase(values.get("upgrade")) || !"13".equals(values.get("sec-websocket-version"))) throw new IOException("Invalid handshake");
        if (!LocalLab15MockServer.verify(key, "GET", "/ws", new byte[0], values.get("x-timestamp"), values.get("x-nonce"), values.get("x-signature"), System.currentTimeMillis() / 1000)) throw new IOException("Unsigned handshake");
        // ЛР15: без валидного билета, привязанного к пользователю и этому каналу, рукопожатие не выполняется.
        String userId = verifyTicket(key, values.get("x-lab-ticket"), channel, System.currentTimeMillis() / 1000);
        if (userId == null) throw new IOException("Ticket rejected");
        byte[] frameKey = derive(key, "lab15/frame-key/v1\n" + userId);
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((websocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
        while (true) {
            int first = in.read(); if (first < 0) return;
            int second = in.read(); if (second < 0) return;
            if ((first & 0x80) == 0 || (first & 0x70) != 0 || (second & 0x80) == 0) throw new IOException("Only masked complete frames supported");
            int opcode = first & 15; int length = second & 127;
            if (length == 126) { byte[] size = read(in, 2); length = (size[0] & 255) * 256 + (size[1] & 255); }
            else if (length == 127) throw new IOException("Frame exceeds local limit");
            byte[] mask = read(in, 4); byte[] data = read(in, length);
            for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            if (opcode == 8) { send(out, 8, data); return; }
            if (opcode == 9) { send(out, 10, data); continue; }
            if (opcode != 1) throw new IOException("Only text frames supported");
            String[] frame = new String(data, StandardCharsets.UTF_8).split("\n", 5);
            long now = System.currentTimeMillis() / 1000;
            if (frame.length != 5 || !(channel + "/request").equals(frame[0])) throw new IOException("Wrong channel");
            byte[] payload = Base64.getDecoder().decode(frame[4]);
            if (!verifyFrame(frameKey, frame[0], frame[1], frame[2], frame[3], payload, now)) throw new IOException("Invalid signed frame");
            String nonce = UUID.randomUUID().toString();
            byte[] response = "owned local metric".getBytes(StandardCharsets.UTF_8);
            String responseChannel = channel + "/response";
            String signature = hmac(frameKey, canonical(responseChannel, now, nonce, response));
            send(out, 1, (responseChannel + "\n" + now + "\n" + nonce + "\n" + signature + "\n" + Base64.getEncoder().encodeToString(response)).getBytes(StandardCharsets.UTF_8));
        }
    }
    static synchronized boolean verifyFrame(byte[] key, String channel, String timestamp, String nonce, String signature, byte[] payload, long now) throws Exception {
        long sent = Long.parseLong(timestamp); long offset = Math.subtractExact(now, sent);
        if (offset < -30 || offset > 30 || payload.length > 65536 || !nonce.matches("[A-Za-z0-9_-]{16,64}") || !signature.matches("[a-fA-F0-9]{64}")) return false;
        if (!MessageDigest.isEqual(hmac(key, canonical(channel, sent, nonce, payload)).getBytes(StandardCharsets.US_ASCII), signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) return false;
        NONCES.entrySet().removeIf(e -> now > e.getValue());
        String nonceKey = channel + ":" + nonce;
        if (NONCES.containsKey(nonceKey) || NONCES.size() >= 50000) return false;
        NONCES.put(nonceKey, sent > Long.MAX_VALUE - 30 ? Long.MAX_VALUE : sent + 30); return true;
    }
    static String canonical(String channel, long timestamp, String nonce, byte[] payload) throws Exception {
        return "WS\n" + channel + "\n" + timestamp + "\n" + nonce + "\n" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }
    static synchronized String verifyTicket(String key, String ticket, String channel, long now) throws Exception {
        if (ticket == null || ticket.length() > 512) return null;
        String[] p = ticket.split("\\.", -1);
        if (p.length != 6 || !"v1".equals(p[0])) return null;
        String user;
        try { user = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8); } catch (IllegalArgumentException bad) { return null; }
        if (!user.matches("[A-Za-z0-9_.-]{1,64}") || !p[2].matches("0|[1-9][0-9]{0,17}") || !p[3].matches("[0-9]{1,18}") ||
            !p[4].matches("[A-Za-z0-9_-]{16,64}") || !p[5].matches("[a-fA-F0-9]{64}")) return null;
        long expires = Long.parseLong(p[3]);
        String canonical = String.join("\n", "LAB15-TICKET", "v1", user, p[2], channel, p[3], p[4]);
        if (!MessageDigest.isEqual(hmac(derive(key, "lab15/ticket-key/v1"), canonical).getBytes(StandardCharsets.US_ASCII),
                p[5].toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) return null;
        if (!channel.startsWith("/lab15/u/" + userTag(user) + "/")) return null;
        if (now > expires || expires - now > 30) return null;
        NONCES.entrySet().removeIf(e -> now > e.getValue());
        String nonceKey = "ticket:" + p[4];
        if (NONCES.containsKey(nonceKey) || NONCES.size() >= 50000) return null;
        NONCES.put(nonceKey, expires);
        return user;
    }
    static byte[] derive(String master, String label) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(master.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
    }
    static String userTag(String user) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(user.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }
    static String hmac(byte[] key, String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
    static String hmac(String key, String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
    private static byte[] read(InputStream in, int count) throws IOException { byte[] b = in.readNBytes(count); if (b.length != count) throw new EOFException(); return b; }
    private static void send(OutputStream out, int opcode, byte[] bytes) throws IOException {
        out.write(0x80 | opcode);
        if (bytes.length < 126) out.write(bytes.length); else { out.write(126); out.write(bytes.length >> 8); out.write(bytes.length & 255); }
        out.write(bytes); out.flush();
    }
}

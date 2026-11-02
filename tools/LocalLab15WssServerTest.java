import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
public final class LocalLab15WssServerTest {
    public static void main(String[] args) throws Exception {
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String master = HexFormat.of().formatHex(random);
        // Кадры: ключ пользователя, окно времени, повтор
        byte[] aliceKey = LocalLab15WssServer.derive(master, "lab15/frame-key/v1\n" + "alice");
        byte[] bobKey = LocalLab15WssServer.derive(master, "lab15/frame-key/v1\n" + "bob");
        String channel = "/lab15/u/" + LocalLab15WssServer.userTag("alice") + "/00000000-0000-0000-0000-000000000001";
        String nonce = "future_wss_nonce_0001"; byte[] payload = "local".getBytes();
        String signature = LocalLab15WssServer.hmac(aliceKey, LocalLab15WssServer.canonical(channel, 130, nonce, payload));
        expect(false, LocalLab15WssServer.verifyFrame(bobKey, channel, "130", nonce, signature, payload, 130)); // чужой ключ
        expect(true, LocalLab15WssServer.verifyFrame(aliceKey, channel, "130", nonce, signature, payload, 100));
        expect(false, LocalLab15WssServer.verifyFrame(aliceKey, channel, "130", nonce, signature, payload, 131));
        expect(false, LocalLab15WssServer.verifyFrame(aliceKey, channel, "130", nonce, signature, payload, 160));
        expect(false, LocalLab15WssServer.verifyFrame(aliceKey, channel, "130", nonce, signature, payload, 161));

        // Билеты рукопожатия
        long now = 1_000;
        String ticket = ticket(master, "alice", 3, channel, now + 30, "ticket_nonce_000000001");
        expect(true, "alice".equals(LocalLab15WssServer.verifyTicket(master, ticket, channel, now)));
        expect(true, LocalLab15WssServer.verifyTicket(master, ticket, channel, now) == null); // повтор
        String other = "/lab15/u/" + LocalLab15WssServer.userTag("alice") + "/00000000-0000-0000-0000-000000000002";
        expect(true, LocalLab15WssServer.verifyTicket(master, ticket(master, "alice", 3, channel, now + 30, "ticket_nonce_000000002"), other, now) == null); // другой канал
        String bobChannel = "/lab15/u/" + LocalLab15WssServer.userTag("bob") + "/00000000-0000-0000-0000-000000000003";
        expect(true, LocalLab15WssServer.verifyTicket(master, ticket(master, "alice", 3, bobChannel, now + 30, "ticket_nonce_000000003"), bobChannel, now) == null); // канал чужого пользователя
        expect(true, LocalLab15WssServer.verifyTicket(master, ticket(master, "alice", 3, channel, now - 1, "ticket_nonce_000000004"), channel, now) == null); // истёк
        expect(true, LocalLab15WssServer.verifyTicket(master, ticket(master, "alice", 3, channel, now + 3600, "ticket_nonce_000000005"), channel, now) == null); // слишком долгий
        expect(true, LocalLab15WssServer.verifyTicket("another-master-key-000", ticket(master, "alice", 3, channel, now + 30, "ticket_nonce_000000006"), channel, now) == null); // чужой мастер
        String tampered = ticket(master, "alice", 3, channel, now + 30, "ticket_nonce_000000007").replace(".3.", ".4.");
        expect(true, LocalLab15WssServer.verifyTicket(master, tampered, channel, now) == null); // подмена версии
        expect(true, LocalLab15WssServer.verifyTicket(master, "v1.!!!.3.1030.ticket_nonce_000000008." + "0".repeat(64), channel, now) == null);
        expect(true, LocalLab15WssServer.verifyTicket(master, null, channel, now) == null);
        System.out.println("Owned local Java WSS frame + user ticket regression: OK");
    }
    /** Тот же формат, что SocketTicketCodec.issue в приложении. */
    static String ticket(String master, String user, long version, String channel, long expires, String nonce) throws Exception {
        String canonical = String.join("\n", "LAB15-TICKET", "v1", user, Long.toString(version), channel, Long.toString(expires), nonce);
        String sig = LocalLab15WssServer.hmac(LocalLab15WssServer.derive(master, "lab15/ticket-key/v1"), canonical);
        String u = Base64.getUrlEncoder().withoutPadding().encodeToString(user.getBytes(StandardCharsets.UTF_8));
        return "v1." + u + "." + version + "." + expires + "." + nonce + "." + sig;
    }
    private static void expect(boolean expected, boolean actual) { if (expected != actual) throw new AssertionError("WSS regression"); }
}

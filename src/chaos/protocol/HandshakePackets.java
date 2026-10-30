package chaos.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Fixed prefixes of otherwise complete, non-TLS startup packets. No password is sent. */
public final class HandshakePackets {
    private static final int CLIENT_PROTOCOL_41 = 0x200;
    private static final int CLIENT_SECURE_CONNECTION = 0x8000;

    private HandshakePackets() { }

    public static byte[] startupPrefix(boolean openGauss, String user, String database) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);
        // Verified against the bundled openGauss 7.0.0-RC2 driver's sendStartupPacket:
        // major=3, minor=51. PostgreSQL's startup version is 3.0.
        data.writeShort(3);
        data.writeShort(openGauss ? 51 : 0);
        writeCString(body, "user");
        writeCString(body, user);
        writeCString(body, "database");
        writeCString(body, database);
        writeCString(body, "application_name");
        writeCString(body, "dbchaos_handshake_stall");
        body.write(0);

        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        new DataOutputStream(packet).writeInt(body.size() + 4);
        body.writeTo(packet);
        // Exactly 12 bytes: full length + version + "user" (without its NUL/value).
        // The declared packet is complete in memory; its remaining bytes are never sent.
        return Arrays.copyOf(packet.toByteArray(), 12);
    }

    public static byte[] mysqlResponsePrefix(byte[] greeting) throws IOException {
        if (greeting.length == 0 || (greeting[0] & 255) != 10) {
            throw new IOException("MySQL 问候不是协议 10，或服务端已拒绝连接");
        }
        int offset = 1;
        while (offset < greeting.length && greeting[offset] != 0) offset++;
        if (offset == greeting.length) throw new IOException("MySQL 问候缺少版本字符串结束符");
        offset++; // end of server version
        offset += 4 + 8; // connection ID, first scramble bytes
        if (offset + 3 > greeting.length || greeting[offset] != 0) {
            throw new IOException("MySQL 问候不完整，无法读取能力标志");
        }
        offset++;
        int capabilities = (greeting[offset] & 255) | ((greeting[offset + 1] & 255) << 8);
        int charset = greeting[offset + 2] & 255;
        if ((capabilities & CLIENT_PROTOCOL_41) == 0) {
            throw new IOException("MySQL 服务端不支持 Protocol 4.1");
        }

        // Negotiate only the capabilities needed for an empty auth response.
        // SSL, compression and plugin-auth extensions are deliberately not enabled.
        int flags = CLIENT_PROTOCOL_41 | (capabilities & CLIENT_SECURE_CONNECTION);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeLittleEndian(body, flags, 4);
        writeLittleEndian(body, 0x1000000, 4); // maximum packet size
        body.write(charset);
        body.write(new byte[23]);
        writeCString(body, "dbchaos_handshake_stall");
        body.write(0); // empty authentication data, never transmitted

        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeLittleEndian(packet, body.size(), 3);
        packet.write(1); // first client response to greeting sequence 0
        body.writeTo(packet);
        // Exactly 8 bytes: packet header + capability flags. All later fields are withheld.
        return Arrays.copyOf(packet.toByteArray(), 8);
    }

    private static void writeCString(ByteArrayOutputStream out, String value) throws IOException {
        if (value == null || value.indexOf('\0') >= 0) throw new IOException("启动参数包含非法空字符");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void writeLittleEndian(ByteArrayOutputStream out, int value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((value >>> (8 * i)) & 255);
    }
}

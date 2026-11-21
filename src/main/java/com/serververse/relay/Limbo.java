package com.serververse.relay;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sonar-style limbo: a tiny fake world the player joins before the real server.
 * The player spawns in the air above an empty chunk and we check that the client falls with
 * real Minecraft gravity for several ticks. Bots that fake movement or never leave the loading
 * screen fail; real clients pass in about a second, then get kicked with "please rejoin".
 *
 * Runs in offline mode (no encryption) since it only checks the client; the real server still
 * does normal (online-mode) login when the verified player rejoins.
 *
 * Packets are pre-built per protocol by tools/limbo-gen into resources/limbo/<protocol>.bin.
 */
final class Limbo {

    enum Outcome { PASSED, FAILED, INCOMPATIBLE, TIMEOUT }

    static final class Session {
        final Outcome outcome;
        final String detail;
        Session(Outcome o, String d) { outcome = o; detail = d; }
    }

    private static final double GRAVITY = 0.08, DRAG = 0.98, EPSILON = 0.005;
    private static final int MAX_PACKET = 1 << 16;

    private static final Map<Integer, Map<String, byte[]>> CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> AVAILABLE = new ConcurrentHashMap<>();

    /** Per protocol: sessions in a row that broke before play, with no pass ever. Used to auto-disable. */
    private static final Map<Integer, AtomicInteger> BROKEN = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> EVER_PASSED = new ConcurrentHashMap<>();
    private static final int AUTO_DISABLE_AFTER = 8;

    private Limbo() { }

    static boolean supports(int protocol) {
        if (EVER_PASSED.get(protocol) == null) {
            AtomicInteger b = BROKEN.get(protocol);
            if (b != null && b.get() >= AUTO_DISABLE_AFTER) return false;
        }
        return AVAILABLE.computeIfAbsent(protocol,
                p -> Limbo.class.getResource("/limbo/" + p + ".bin") != null);
    }

    private static Map<String, byte[]> data(int protocol) throws IOException {
        Map<String, byte[]> m = CACHE.get(protocol);
        if (m != null) return m;
        try (InputStream raw = Limbo.class.getResourceAsStream("/limbo/" + protocol + ".bin")) {
            if (raw == null) throw new IOException("no limbo data for protocol " + protocol);
            DataInputStream in = new DataInputStream(raw);
            int n = readVarInt(in);
            m = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) {
                byte[] k = new byte[readVarInt(in)];
                in.readFully(k);
                byte[] v = new byte[readVarInt(in)];
                in.readFully(v);
                m.put(new String(k, StandardCharsets.UTF_8), v);
            }
        }
        CACHE.put(protocol, m);
        return m;
    }

    /**
     * Runs the check. The handshake and Login Start were already read from {@code in}.
     * Always ends with a disconnect being sent (or the socket being unusable).
     */
    static Session run(int protocol, String name, Socket sock, DataInputStream in, OutputStream out,
                       long timeoutMs, int ticksRequired, String passMsg, String failMsg,
                       RelayClient.Log log) throws IOException {
        Map<String, byte[]> d = data(protocol);
        long deadline = System.currentTimeMillis() + timeoutMs;
        String stage = "login";
        try {
            // ---------------- login
            ByteArrayOutputStream ls = new ByteArrayOutputStream();
            ls.write(d.get("login.success.id"));
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            ls.write(ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array());
            writeString(ls, name);
            ls.write(d.get("login.success.suffix"));
            send(out, ls.toByteArray());
            waitFor(sock, in, varIntValue(d.get("sb.login_ack")), deadline);

            // ---------------- configuration
            stage = "configuration";
            send(out, d.get("cfg.feature_flags"));
            send(out, d.get("cfg.known_packs"));
            byte[] packs = waitFor(sock, in, varIntValue(d.get("sb.cfg.known_packs")), deadline);
            String version = new String(stripLen(d.get("version")), StandardCharsets.UTF_8);
            boolean known = clientKnowsCore(packs, version);
            int regs = varIntValue(d.get("cfg.registry.count"));
            for (int i = 0; i < regs; i++) {
                send(out, d.get((known ? "cfg.registry.known." : "cfg.registry.full.") + i));
            }
            send(out, d.get("cfg.finish"));
            waitFor(sock, in, varIntValue(d.get("sb.cfg.finish")), deadline);

            // ---------------- play
            stage = "play";
            send(out, d.get("play.login"));
            send(out, d.get("play.teleport"));
            send(out, d.get("play.chunks_start"));
            send(out, d.get("play.view_pos"));
            send(out, d.get("play.batch_start"));
            send(out, d.get("play.chunk"));
            send(out, d.get("play.batch_end"));

            int idConfirm = varIntValue(d.get("sb.play.teleport_confirm"));
            int idPos = varIntValue(d.get("sb.play.position"));
            int idPosLook = varIntValue(d.get("sb.play.position_look"));
            double spawnY = Double.parseDouble(new String(d.get("play.spawn_y"), StandardCharsets.US_ASCII));

            boolean confirmed = false;
            Double lastY = null;
            Double lastDelta = null;
            int good = 0;
            while (good < ticksRequired) {
                byte[] p = readPacket(sock, in, deadline);
                Buf b = new Buf(p);
                int id = b.varInt();
                if (id == idConfirm) {
                    confirmed = true;
                    continue;
                }
                if (id != idPos && id != idPosLook) continue;
                if (!confirmed) return fail(out, protocol, failMsg, "moved before confirming teleport");
                b.f64(); // x
                double y = b.f64();
                b.f64(); // z
                if (id == idPosLook) { b.f32(); b.f32(); }
                int flags = b.u8();
                if (y >= spawnY && lastY == null) continue; // still on the loading screen
                if ((flags & 1) != 0) return fail(out, protocol, failMsg, "claimed to be on ground in mid-air");

                double prev = lastY == null ? spawnY : lastY;
                double delta = y - prev;
                double expected = (lastDelta == null ? 0 : lastDelta);
                expected = (expected - GRAVITY) * DRAG;
                if (Math.abs(delta - expected) > EPSILON) {
                    return fail(out, protocol, failMsg, String.format("bad gravity (dy %.4f, expected %.4f)", delta, expected));
                }
                lastY = y;
                lastDelta = delta;
                good++;
            }
            EVER_PASSED.put(protocol, true);
            BROKEN.remove(protocol);
            disconnect(out, varIntValue(d.get("play.disconnect.id")), passMsg);
            return new Session(Outcome.PASSED, null);
        } catch (java.net.SocketTimeoutException | TimeoutError e) {
            if (!stage.equals("play")) countBroken(protocol, log);
            tryDisconnect(out, d, stage, failMsg);
            return new Session(Outcome.TIMEOUT, "timed out in " + stage);
        } catch (java.io.EOFException | java.net.SocketException e) {
            // Client left. Before play that may mean it rejected our packets (version mismatch).
            if (!stage.equals("play")) {
                countBroken(protocol, log);
                return new Session(Outcome.INCOMPATIBLE, "client left during " + stage);
            }
            return new Session(Outcome.TIMEOUT, "client left during play");
        }
    }

    private static void countBroken(int protocol, RelayClient.Log log) {
        if (EVER_PASSED.get(protocol) != null) return;
        int n = BROKEN.computeIfAbsent(protocol, k -> new AtomicInteger()).incrementAndGet();
        if (n == AUTO_DISABLE_AFTER) {
            log.warn("[AntiBot] Limbo check keeps failing for protocol " + protocol
                    + " before any player passed - using the rejoin check for that version instead.");
        }
    }

    private static Session fail(OutputStream out, int protocol, String msg, String detail) throws IOException {
        disconnect(out, varIntValue(data(protocol).get("play.disconnect.id")), msg);
        return new Session(Outcome.FAILED, detail);
    }

    private static void tryDisconnect(OutputStream out, Map<String, byte[]> d, String stage, String msg) {
        try {
            if (stage.equals("configuration")) disconnect(out, varIntValue(d.get("cfg.disconnect.id")), msg);
            else if (stage.equals("play")) disconnect(out, varIntValue(d.get("play.disconnect.id")), msg);
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ packets

    private static boolean clientKnowsCore(byte[] packet, String version) {
        try {
            Buf b = new Buf(packet);
            b.varInt(); // id
            int n = b.varInt();
            for (int i = 0; i < n && i < 64; i++) {
                String ns = b.string(), id = b.string(), ver = b.string();
                if (ns.equals("minecraft") && id.equals("core") && ver.equals(version)) return true;
            }
        } catch (IOException ignored) { }
        return false;
    }

    /** Reads packets until one with {@code wantedId} arrives (others, like client settings, are skipped). */
    private static byte[] waitFor(Socket sock, DataInputStream in, int wantedId, long deadline) throws IOException {
        for (int i = 0; i < 64; i++) {
            byte[] p = readPacket(sock, in, deadline);
            if (new Buf(p).varInt() == wantedId) return p;
        }
        throw new IOException("too many unexpected packets");
    }

    private static byte[] readPacket(Socket sock, DataInputStream in, long deadline) throws IOException {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) throw new TimeoutError();
        sock.setSoTimeout((int) Math.max(1, left));
        int len = readVarInt(in);
        if (len <= 0 || len > MAX_PACKET) throw new IOException("bad packet length " + len);
        byte[] p = new byte[len];
        in.readFully(p);
        return p;
    }

    private static void send(OutputStream out, byte[] packet) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(packet.length + 5);
        writeVarInt(f, packet.length);
        f.write(packet);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Config/play disconnect: text component as nameless network NBT (1.20.3+). */
    private static void disconnect(OutputStream out, int id, String message) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, id);
        DataOutputStream nbt = new DataOutputStream(b);
        nbt.writeByte(0x0A);              // compound (nameless root)
        nbt.writeByte(0x08); nbt.writeUTF("text"); nbt.writeUTF(message);
        nbt.writeByte(0x08); nbt.writeUTF("color"); nbt.writeUTF("#ffa632");
        nbt.writeByte(0x00);              // end
        nbt.flush();
        send(out, b.toByteArray());
    }

    // ------------------------------------------------------------------ encoding

    private static final class TimeoutError extends IOException { }

    private static byte[] stripLen(byte[] s) throws IOException {
        Buf b = new Buf(s);
        int n = b.varInt();
        byte[] r = new byte[n];
        System.arraycopy(s, s.length - n, r, 0, n);
        return r;
    }

    private static int varIntValue(byte[] b) throws IOException {
        return new Buf(b).varInt();
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0, shift = 0, b;
        do {
            b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("varint too long");
        } while ((b & 0x80) != 0);
        return v;
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private static final class Buf {
        private final ByteBuffer d;
        Buf(byte[] b) { d = ByteBuffer.wrap(b); }

        int varInt() throws IOException {
            int v = 0, shift = 0, b;
            do {
                if (!d.hasRemaining()) throw new IOException("truncated");
                b = d.get() & 0xFF;
                v |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new IOException("varint too long");
            } while ((b & 0x80) != 0);
            return v;
        }

        String string() throws IOException {
            int n = varInt();
            if (n < 0 || n > d.remaining()) throw new IOException("bad string");
            byte[] b = new byte[n];
            d.get(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        double f64() throws IOException { need(8); return d.getDouble(); }
        float f32() throws IOException { need(4); return d.getFloat(); }
        int u8() throws IOException { need(1); return d.get() & 0xFF; }
        private void need(int n) throws IOException { if (d.remaining() < n) throw new IOException("truncated"); }
    }
}

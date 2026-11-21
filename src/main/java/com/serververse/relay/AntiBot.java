package com.serververse.relay;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Sonar-style anti-bot for Transit Play.
 *
 * Runs on every player connection the relay hands us, BEFORE we open a socket to the local
 * Minecraft server. It reads the (unencrypted, uncompressed) Handshake and Login Start packets,
 * runs its checks, and either kicks the connection with a normal Minecraft disconnect screen
 * or hands back the bytes it read so they can be replayed to the server untouched.
 *
 * Checks:
 *  - slow / malformed handshakes (timeouts, size caps, bad packet ids, bad states)
 *  - per-IP connection, login and server-list-ping rate limits
 *  - concurrent pre-login sockets (global and per IP)
 *  - username validity and distinct-names-per-IP
 *  - protocol version floor
 *  - attack mode: switches on when new logins per second exceed a threshold
 *  - reconnect verification: an unverified player is kicked once with "please rejoin" and must
 *    come back within a time window (not instantly, not too late). Most bot frameworks fail this.
 *  - temporary blacklist for IPs that keep failing checks
 *  - verified cache (IP + name), saved to disk, so real players only ever verify once
 *
 * No Bukkit dependency, so it also works in RelayClient's standalone mode.
 */
public final class AntiBot {

    // ------------------------------------------------------------------ config

    public enum VerifyMode { ALWAYS, DURING_ATTACK, NEVER }
    public enum VerifyMethod { LIMBO, REJOIN }

    public static final class Config {
        public boolean enabled = true;
        public VerifyMode verify = VerifyMode.DURING_ATTACK;
        /** LIMBO = fake-world gravity check (1.20.5+, older versions fall back to REJOIN). */
        public VerifyMethod method = VerifyMethod.LIMBO;
        public long limboTimeoutMs = 15_000;
        public int limboTicks = 8;
        public int limboMaxSessions = 100;
        public long verifyMinRejoinMs = 1_500;
        public long verifyMaxRejoinMs = 60_000;
        public long verifiedExpiryMs = TimeUnit.DAYS.toMillis(7);

        public int attackThresholdPerSecond = 8;
        public long attackCooldownMs = 30_000;
        /** During an attack, at most this many unverified players are let through to verify per second. */
        public int attackMaxNewPerSecond = 5;

        public int maxPreLoginGlobal = 200;
        public int maxPreLoginPerIp = 3;
        public long handshakeTimeoutMs = 5_000;

        public int maxConnectionsPerIpPer10s = 6;
        public int maxLoginsPerIpPer10s = 3;
        public int maxPingsPerIpPer10s = 10;
        public int maxNamesPerIpPer10m = 3;

        public int minProtocol = 47; // 1.8
        public String namePattern = "^[a-zA-Z0-9_]{3,16}$";

        public int blacklistAfterFails = 5;
        public long blacklistDurationMs = TimeUnit.MINUTES.toMillis(10);

        public String msgVerify = "Serververse Shield is checking your connection.\nPlease rejoin the server.";
        public String msgAttack = "This server is under a bot attack.\nPlease try again in a few seconds.";
        public String msgThrottled = "You are connecting too fast.\nPlease wait a moment and try again.";
        public String msgInvalidName = "Invalid username.";
        public String msgOutdated = "Your Minecraft version is not supported.";
        public String msgBlacklisted = "Too many failed connection attempts.\nPlease try again later.";
        public String msgVerified = "Verified! Please rejoin the server.";
        public String msgVerifyFailed = "Verification failed.\nPlease wait a few seconds, then rejoin.";
    }

    /** What {@link #inspect} decided. */
    public static final class Result {
        public final boolean allow;
        /** Bytes already read from the player; replay these to the server before piping. */
        public final byte[] replay;
        public final String reason;
        public final String name;

        private Result(boolean allow, byte[] replay, String reason, String name) {
            this.allow = allow;
            this.replay = replay;
            this.reason = reason;
            this.name = name;
        }
    }

    private static final int MAX_HANDSHAKE = 4096;   // Forge appends mod data to the host field
    private static final int MAX_LOGIN_START = 512;

    private final Config cfg;
    private final RelayClient.Log log;
    private final Path verifiedFile;
    private final Pattern namePattern;
    private final ScheduledExecutorService timer;

    private final Map<String, Long> verified = new ConcurrentHashMap<>();       // ip|name -> verified at
    private final Map<String, Long> pending = new ConcurrentHashMap<>();        // ip|name -> first attempt at
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();      // ip -> until
    private final Map<String, IpState> ips = new ConcurrentHashMap<>();
    private final AtomicInteger preLogin = new AtomicInteger();
    private final AtomicInteger limboSessions = new AtomicInteger();

    private final AtomicInteger loginsThisSecond = new AtomicInteger();
    private final AtomicInteger newThisSecond = new AtomicInteger();
    private volatile long attackUntil;
    private volatile boolean attackLogged;

    private final AtomicLong statAllowed = new AtomicLong();
    private final AtomicLong statBlocked = new AtomicLong();
    private final AtomicLong statVerified = new AtomicLong();

    public AntiBot(Config cfg, RelayClient.Log log, Path verifiedFile) {
        this.cfg = cfg;
        this.log = log;
        this.verifiedFile = verifiedFile;
        this.namePattern = Pattern.compile(cfg.namePattern);
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "serververse-antibot");
            t.setDaemon(true);
            return t;
        });
        load();
        timer.scheduleAtFixedRate(this::tickSecond, 1, 1, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
        timer.scheduleAtFixedRate(this::save, 5, 5, TimeUnit.MINUTES);
    }

    public void shutdown() {
        timer.shutdownNow();
        save();
    }

    // ------------------------------------------------------------------ main entry

    /**
     * Reads the start of a player connection and decides whether it may reach the server.
     * If it is rejected, a disconnect packet has already been written to {@code out}.
     */
    public Result inspect(String ip, InputStream rawIn, OutputStream out, java.net.Socket sock) throws IOException {
        if (!cfg.enabled) return new Result(true, new byte[0], null, null);
        if (ip == null || ip.isEmpty()) ip = "unknown";
        long now = System.currentTimeMillis();

        Long until = blacklist.get(ip);
        boolean blacklisted = false;
        if (until != null) {
            if (until > now) blacklisted = true;
            else blacklist.remove(ip);
        }

        IpState st = ips.computeIfAbsent(ip, k -> new IpState());
        if (st.connections.hit(now, 10_000) > cfg.maxConnectionsPerIpPer10s) {
            return block(ip, null, "connection flood", null, out, true);
        }

        if (preLogin.incrementAndGet() > cfg.maxPreLoginGlobal || st.preLogin.incrementAndGet() > cfg.maxPreLoginPerIp) {
            preLogin.decrementAndGet();
            st.preLogin.decrementAndGet();
            // can't count on reading anything; just drop it
            return block(ip, null, "too many open handshakes", null, null, true);
        }
        int oldTimeout = sock.getSoTimeout();
        try {
            sock.setSoTimeout((int) cfg.handshakeTimeoutMs);
            return inspectPackets(ip, st, new DataInputStream(rawIn), out, now, blacklisted, sock);
        } catch (java.net.SocketTimeoutException e) {
            return block(ip, null, "handshake timeout", null, null, true);
        } catch (ProtocolError | EOFException e) {
            return block(ip, null, "bad packet: " + e.getMessage(), null, null, true);
        } finally {
            preLogin.decrementAndGet();
            st.preLogin.decrementAndGet();
            try { sock.setSoTimeout(oldTimeout); } catch (IOException ignored) { }
        }
    }

    private Result inspectPackets(String ip, IpState st, DataInputStream in, OutputStream out, long now, boolean blacklisted, java.net.Socket sock) throws IOException {
        ByteArrayOutputStream replay = new ByteArrayOutputStream(256);

        int first = in.readUnsignedByte();
        replay.write(first);
        if (first == 0xFE) {
            // Legacy (pre-1.7) server list ping - treat as a ping
            if (st.pings.hit(now, 10_000) > cfg.maxPingsPerIpPer10s) return block(ip, null, "ping flood", null, null, false);
            return allow(replay, null);
        }

        // ---- Handshake
        int hsLen = readVarInt(in, replay, first);
        byte[] hs = readPacket(in, hsLen, MAX_HANDSHAKE, replay);
        Buf b = new Buf(hs);
        if (b.varInt() != 0x00) throw new ProtocolError("handshake id");
        int protocol = b.varInt();
        b.string(255 * 4 + MAX_HANDSHAKE); // host (may include Forge data)
        b.u16();                            // port
        int next = b.varInt();

        if (next == 1) {
            if (st.pings.hit(now, 10_000) > cfg.maxPingsPerIpPer10s) return block(ip, null, "ping flood", null, null, false);
            return allow(replay, null);
        }
        if (next != 2 && next != 3) throw new ProtocolError("next state " + next);

        // ---- Login Start
        int lsFirst = in.readUnsignedByte();
        replay.write(lsFirst);
        int len = readVarInt(in, replay, lsFirst);
        byte[] ls = readPacket(in, len, MAX_LOGIN_START, replay);
        Buf l = new Buf(ls);
        if (l.varInt() != 0x00) throw new ProtocolError("login start id");
        String name = l.string(16 * 4);

        // From here on we can show the player a real kick message.
        loginsThisSecond.incrementAndGet();

        if (blacklisted) return block(ip, name, "blacklisted", cfg.msgBlacklisted, out, false);
        if (protocol < cfg.minProtocol) return block(ip, name, "outdated protocol " + protocol, cfg.msgOutdated, out, false);
        if (!namePattern.matcher(name).matches()) return block(ip, name, "invalid name", cfg.msgInvalidName, out, true);

        String key = ip + "|" + name.toLowerCase();
        Long v = verified.get(key);
        boolean isVerified = v != null && now - v < cfg.verifiedExpiryMs;
        if (isVerified) return allow(replay, name); // still bound by the per-IP connection limit above

        if (st.logins.hit(now, 10_000) > cfg.maxLoginsPerIpPer10s) return block(ip, name, "login flood", cfg.msgThrottled, out, true);

        if (st.names.hitDistinct(name.toLowerCase(), now, 600_000) > cfg.maxNamesPerIpPer10m) {
            return block(ip, name, "too many names from one IP", cfg.msgThrottled, out, true);
        }

        boolean attack = underAttack();
        if (attack && newThisSecond.incrementAndGet() > cfg.attackMaxNewPerSecond) {
            return block(ip, name, "attack queue full", cfg.msgAttack, out, false);
        }

        boolean mustVerify = cfg.verify == VerifyMode.ALWAYS || (cfg.verify == VerifyMode.DURING_ATTACK && attack);
        if (!mustVerify) return allow(replay, name);

        // ---- Limbo (gravity) verification
        if (cfg.method == VerifyMethod.LIMBO && Limbo.supports(protocol)) {
            if (limboSessions.incrementAndGet() > cfg.limboMaxSessions) {
                limboSessions.decrementAndGet();
                return block(ip, name, "limbo full", cfg.msgAttack, out, false);
            }
            Limbo.Session session;
            try {
                session = Limbo.run(protocol, name, sock, in, out, cfg.limboTimeoutMs, cfg.limboTicks,
                        cfg.msgVerified, cfg.msgVerifyFailed, log);
            } finally {
                limboSessions.decrementAndGet();
            }
            statBlocked.incrementAndGet(); // the limbo connection itself never reaches the server
            switch (session.outcome) {
                case PASSED -> {
                    verified.put(key, System.currentTimeMillis());
                    statVerified.incrementAndGet();
                    return new Result(false, null, "verified in limbo", name);
                }
                case FAILED -> {
                    block(ip, name, "limbo: " + session.detail, null, null, true);
                    return new Result(false, null, "limbo: " + session.detail, name);
                }
                default -> {
                    return new Result(false, null, "limbo: " + session.detail, name);
                }
            }
        }

        // ---- Reconnect verification (versions without limbo data, or method: rejoin)
        Long firstSeen = pending.get(key);
        if (firstSeen == null || now - firstSeen > cfg.verifyMaxRejoinMs) {
            pending.put(key, now);
            kick(out, cfg.msgVerify);
            statBlocked.incrementAndGet();
            return new Result(false, null, "verification started", name);
        }
        long gap = now - firstSeen;
        if (gap < cfg.verifyMinRejoinMs) {
            pending.put(key, now); // restart the clock
            return block(ip, name, "rejoined too fast (" + gap + "ms)", cfg.msgVerifyFailed, out, true);
        }
        pending.remove(key);
        verified.put(key, now);
        statVerified.incrementAndGet();
        return allow(replay, name);
    }

    // ------------------------------------------------------------------ decisions

    private Result allow(ByteArrayOutputStream replay, String name) {
        statAllowed.incrementAndGet();
        return new Result(true, replay.toByteArray(), null, name);
    }

    private Result block(String ip, String name, String reason, String kickMsg, OutputStream out, boolean countsAsFail) {
        statBlocked.incrementAndGet();
        if (kickMsg != null && out != null) {
            try { kick(out, kickMsg); } catch (IOException ignored) { }
        }
        if (countsAsFail) {
            IpState st = ips.computeIfAbsent(ip, k -> new IpState());
            if (st.fails.incrementAndGet() >= cfg.blacklistAfterFails) {
                blacklist.put(ip, System.currentTimeMillis() + cfg.blacklistDurationMs);
                st.fails.set(0);
                if (!underAttack()) log.warn("[AntiBot] Blacklisted " + ip + " for "
                        + (cfg.blacklistDurationMs / 60_000) + " min (last: " + reason + ")");
            }
        }
        return new Result(false, null, reason, name);
    }

    private static void kick(OutputStream out, String message) throws IOException {
        // Login-state Disconnect (id 0x00) takes a JSON text component string on every version.
        String json = "{\"text\":\"" + jsonEscape(message) + "\",\"color\":\"#ffa632\"}";
        byte[] str = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0x00);
        writeVarInt(body, str.length);
        body.write(str);
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        writeVarInt(pkt, body.size());
        body.writeTo(pkt);
        out.write(pkt.toByteArray());
        out.flush();
    }

    // ------------------------------------------------------------------ attack detection

    public boolean underAttack() {
        return System.currentTimeMillis() < attackUntil;
    }

    private void tickSecond() {
        int logins = loginsThisSecond.getAndSet(0);
        newThisSecond.set(0);
        long now = System.currentTimeMillis();
        if (logins > cfg.attackThresholdPerSecond) {
            attackUntil = now + cfg.attackCooldownMs;
            if (!attackLogged) {
                attackLogged = true;
                log.warn("[AntiBot] Bot attack detected (" + logins + " logins/s). Attack mode ON - "
                        + "new players must verify.");
            }
        } else if (attackLogged && now >= attackUntil) {
            attackLogged = false;
            log.info("[AntiBot] Attack over. Attack mode OFF. Blocked so far: " + statBlocked.get());
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(t -> now - t > cfg.verifyMaxRejoinMs);
        blacklist.values().removeIf(t -> t < now);
        verified.values().removeIf(t -> now - t > cfg.verifiedExpiryMs);
        ips.entrySet().removeIf(e -> e.getValue().idleSince(now) > 600_000 && e.getValue().preLogin.get() == 0);
    }

    // ------------------------------------------------------------------ admin

    public String stats() {
        return "attack mode: " + (underAttack() ? "ON" : "off")
                + " | allowed " + statAllowed.get()
                + " | blocked " + statBlocked.get()
                + " | verified " + statVerified.get() + " (cached " + verified.size() + ")"
                + " | blacklisted IPs " + blacklist.size();
    }

    public boolean unblock(String ip) {
        IpState st = ips.get(ip);
        if (st != null) st.fails.set(0);
        return blacklist.remove(ip) != null;
    }

    public void clearVerified() {
        verified.clear();
        save();
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (verifiedFile == null || !Files.exists(verifiedFile)) return;
        long now = System.currentTimeMillis();
        try {
            List<String> lines = Files.readAllLines(verifiedFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] p = line.trim().split(" ");
                if (p.length != 2) continue;
                try {
                    long t = Long.parseLong(p[1]);
                    if (now - t < cfg.verifiedExpiryMs) verified.put(p[0], t);
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException e) {
            log.warn("[AntiBot] Could not read " + verifiedFile + ": " + e.getMessage());
        }
    }

    private synchronized void save() {
        if (verifiedFile == null) return;
        try {
            Files.createDirectories(verifiedFile.getParent());
            Path tmp = verifiedFile.resolveSibling(verifiedFile.getFileName() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, Long> e : verified.entrySet()) {
                    w.write(e.getKey() + " " + e.getValue());
                    w.newLine();
                }
            }
            Files.move(tmp, verifiedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("[AntiBot] Could not save " + verifiedFile + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ per-IP state

    private static final class IpState {
        final Window connections = new Window();
        final Window logins = new Window();
        final Window pings = new Window();
        final NameWindow names = new NameWindow();
        final AtomicInteger preLogin = new AtomicInteger();
        final AtomicInteger fails = new AtomicInteger();

        long idleSince(long now) {
            return now - Math.max(connections.last, Math.max(logins.last, pings.last));
        }
    }

    /** Sliding-window hit counter. */
    private static final class Window {
        private final Deque<Long> hits = new ArrayDeque<>();
        volatile long last;

        synchronized int hit(long now, long windowMs) {
            last = now;
            hits.addLast(now);
            while (!hits.isEmpty() && now - hits.peekFirst() > windowMs) hits.pollFirst();
            if (hits.size() > 256) hits.pollFirst();
            return hits.size();
        }
    }

    /** Distinct names seen from one IP inside a window. */
    private static final class NameWindow {
        private final Map<String, Long> seen = new java.util.LinkedHashMap<>();

        synchronized int hitDistinct(String name, long now, long windowMs) {
            seen.values().removeIf(t -> now - t > windowMs);
            seen.put(name, now);
            if (seen.size() > 64) seen.remove(seen.keySet().iterator().next());
            return seen.size();
        }
    }

    // ------------------------------------------------------------------ protocol helpers

    private static final class ProtocolError extends IOException {
        ProtocolError(String m) { super(m); }
    }

    private static byte[] readPacket(DataInputStream in, int len, int max, ByteArrayOutputStream replay) throws IOException {
        if (len <= 0 || len > max) throw new ProtocolError("packet length " + len);
        byte[] data = new byte[len];
        in.readFully(data);
        replay.write(data);
        return data;
    }

    /** Reads a VarInt whose first byte the caller already read and wrote to replay. */
    private static int readVarInt(DataInputStream in, ByteArrayOutputStream replay, int firstByte) throws IOException {
        int value = firstByte & 0x7F, shift = 7, b = firstByte;
        while ((b & 0x80) != 0) {
            if (shift > 28) throw new ProtocolError("varint too long");
            b = in.readUnsignedByte();
            replay.write(b);
            value |= (b & 0x7F) << shift;
            shift += 7;
        }
        return value;
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static final class Buf {
        private final byte[] d;
        private int p;

        Buf(byte[] d) { this.d = d; }

        int varInt() throws ProtocolError {
            int value = 0, shift = 0, b;
            do {
                if (p >= d.length) throw new ProtocolError("truncated varint");
                b = d[p++] & 0xFF;
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new ProtocolError("varint too long");
            } while ((b & 0x80) != 0);
            return value;
        }

        String string(int maxBytes) throws ProtocolError {
            int len = varInt();
            if (len < 0 || len > maxBytes || p + len > d.length) throw new ProtocolError("string length " + len);
            String s = new String(d, p, len, StandardCharsets.UTF_8);
            p += len;
            return s;
        }

        int u16() throws ProtocolError {
            if (p + 2 > d.length) throw new ProtocolError("truncated short");
            int v = ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
            p += 2;
            return v;
        }
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

}

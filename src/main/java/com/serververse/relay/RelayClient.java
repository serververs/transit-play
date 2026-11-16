package com.serververse.relay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tunnel client. Keeps one control connection to the relay; for every player the relay
 * announces, it opens a data connection to the relay and a connection to the local
 * Minecraft server, and pipes bytes between them.
 *
 * Has no Bukkit dependency, so it can also run standalone (see {@link #main}).
 */
public final class RelayClient {

    public interface Log {
        void info(String msg);
        void warn(String msg);
    }

    public static final class Settings {
        public String tunnelHost;
        public int tunnelPort;
        public String agentId;
        public String secret;
        public String localHost = "127.0.0.1";
        public int localPort = 25565;
        public boolean proxyProtocol;
    }

    private static final int BUFFER = 16 * 1024;

    private final Settings cfg;
    private final Log log;
    private final ExecutorService pool;
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong total = new AtomicLong();

    private volatile boolean running;
    private volatile Socket control;
    private volatile String state = "stopped";
    private volatile String publicAddress;
    private Thread controlThread;

    public RelayClient(Settings cfg, Log log) {
        this.cfg = cfg;
        this.log = log;
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "serververse-relay-pipe");
            t.setDaemon(true);
            return t;
        });
    }

    public String state() { return state; }
    public String publicAddress() { return publicAddress; }
    public int activeConnections() { return active.get(); }
    public long totalConnections() { return total.get(); }

    public synchronized void start() {
        if (running) return;
        running = true;
        controlThread = new Thread(this::controlLoop, "serververse-relay-control");
        controlThread.setDaemon(true);
        controlThread.start();
    }

    public synchronized void stop() {
        running = false;
        state = "stopped";
        closeQuietly(control);
        if (controlThread != null) controlThread.interrupt();
        for (Socket s : openSockets) closeQuietly(s);
        pool.shutdownNow();
    }

    /** Drops the control connection; the loop reconnects immediately. */
    public void reconnect() {
        closeQuietly(control);
    }

    // ------------------------------------------------------------------ control

    private void controlLoop() {
        long backoff = 1000;
        while (running) {
            boolean wasReady = false;
            try {
                wasReady = runControl();
            } catch (FatalError e) {
                log.warn("Relay rejected this server: " + e.getMessage()
                        + ". Clear agent-id and agent-secret in config.yml to register again.");
                state = "error: " + e.getMessage();
                running = false;
                return;
            } catch (IOException e) {
                if (running) log.warn("Relay connection lost: " + e.getMessage());
            }
            if (!running) break;
            if (wasReady) backoff = 1000;
            state = "reconnecting";
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                break;
            }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }

    /** @return true if the session reached "ready" before ending */
    private boolean runControl() throws IOException {
        state = "connecting";
        Socket s = new Socket();
        control = s;
        boolean ready = false;
        try {
            s.connect(new InetSocketAddress(cfg.tunnelHost, cfg.tunnelPort), 10_000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(15_000);
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

            writeLine(out, MiniJson.obj("type", "hello", "agentId", cfg.agentId, "secret", cfg.secret, "version", 1));

            String line;
            while ((line = in.readLine()) != null) {
                Map<String, Object> msg;
                try {
                    msg = MiniJson.parseObject(line);
                } catch (RuntimeException e) {
                    continue;
                }
                String type = MiniJson.str(msg, "type");
                if (type == null) continue;
                switch (type) {
                    case "ready" -> {
                        ready = true;
                        publicAddress = MiniJson.str(msg, "publicAddress");
                        int hb = MiniJson.num(msg, "heartbeatMs", 20_000);
                        s.setSoTimeout(hb * 3); // no ping for 3 intervals = dead link
                        state = "online";
                        log.info("Tunnel online! Players can join at " + publicAddress);
                    }
                    case "ping" -> writeLine(out, MiniJson.obj("type", "pong"));
                    case "open" -> {
                        String connId = MiniJson.str(msg, "connId");
                        String ip = MiniJson.str(msg, "remoteAddr");
                        int port = MiniJson.num(msg, "remotePort", 0);
                        pool.submit(() -> handleOpen(connId, ip, port));
                    }
                    case "error" -> {
                        String m = MiniJson.str(msg, "message");
                        if (!ready && m != null && m.startsWith("invalid")) throw new FatalError(m);
                        throw new IOException("relay: " + m);
                    }
                    default -> { /* ignore unknown messages for forward compatibility */ }
                }
            }
            throw new IOException("relay closed the connection");
        } catch (IOException e) {
            if (!ready) throw e;
            if (running) log.warn("Relay connection lost: " + e.getMessage() + " - reconnecting");
            return true;
        } finally {
            closeQuietly(s);
            publicAddress = null;
        }
    }

    // ------------------------------------------------------------------ data

    private void handleOpen(String connId, String playerIp, int playerPort) {
        Socket local = null;
        Socket data = null;
        try {
            data = new Socket();
            openSockets.add(data);
            data.connect(new InetSocketAddress(cfg.tunnelHost, cfg.tunnelPort), 10_000);
            data.setTcpNoDelay(true);
            writeLine(data.getOutputStream(),
                    MiniJson.obj("type", "attach", "agentId", cfg.agentId, "secret", cfg.secret, "connId", connId));

            local = new Socket();
            openSockets.add(local);
            try {
                local.connect(new InetSocketAddress(cfg.localHost, cfg.localPort), 3_000);
            } catch (IOException e) {
                // Attaching and closing makes the relay drop the player right away instead of after a timeout
                log.warn("Cannot reach local server " + cfg.localHost + ":" + cfg.localPort + " - " + e.getMessage());
                return;
            }
            local.setTcpNoDelay(true);

            if (cfg.proxyProtocol) {
                local.getOutputStream().write(proxyHeader(playerIp, playerPort).getBytes(StandardCharsets.US_ASCII));
            }

            active.incrementAndGet();
            total.incrementAndGet();
            try {
                final Socket a = data, b = local;
                pool.submit(() -> copy(a, b));
                copy(b, a);
            } finally {
                active.decrementAndGet();
            }
        } catch (IOException e) {
            log.warn("Player connection " + connId + " failed: " + e.getMessage());
        } finally {
            if (local != null) { closeQuietly(local); openSockets.remove(local); }
            if (data != null) { closeQuietly(data); openSockets.remove(data); }
        }
    }

    private String proxyHeader(String ip, int port) {
        boolean v6 = ip != null && ip.contains(":");
        String src = (ip == null || ip.isEmpty()) ? (v6 ? "::1" : "127.0.0.1") : ip;
        String dst = v6 ? "::1" : "127.0.0.1";
        return "PROXY " + (v6 ? "TCP6 " : "TCP4 ") + src + " " + dst + " " + port + " " + cfg.localPort + "\r\n";
    }

    /** Copies from -> to until EOF/error, then closes both so the other direction ends too. */
    private static void copy(Socket from, Socket to) {
        byte[] buf = new byte[BUFFER];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        synchronized (out) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static final class FatalError extends IOException {
        FatalError(String m) { super(m); }
    }

    /** Standalone mode: java -cp SerververseRelay.jar com.serververse.relay.RelayClient host port agentId secret localHost localPort */
    public static void main(String[] args) throws InterruptedException {
        if (args.length < 6) {
            System.err.println("usage: RelayClient <tunnelHost> <tunnelPort> <agentId> <secret> <localHost> <localPort> [proxy]");
            System.exit(1);
        }
        Settings s = new Settings();
        s.tunnelHost = args[0];
        s.tunnelPort = Integer.parseInt(args[1]);
        s.agentId = args[2];
        s.secret = args[3];
        s.localHost = args[4];
        s.localPort = Integer.parseInt(args[5]);
        s.proxyProtocol = args.length > 6 && args[6].equals("proxy");
        new RelayClient(s, new Log() {
            public void info(String m) { System.out.println("[Serververse Relay] " + m); }
            public void warn(String m) { System.out.println("[Serververse Relay] WARN " + m); }
        }).start();
        Thread.currentThread().join();
    }
}

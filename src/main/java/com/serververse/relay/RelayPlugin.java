package com.serververse.relay;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class RelayPlugin extends JavaPlugin implements CommandExecutor {

    private volatile RelayClient client;
    private volatile AntiBot antiBot;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getCommand("relay") != null) getCommand("relay").setExecutor(this);
        // boot() may open sockets, so never on the main thread
        getServer().getScheduler().runTaskAsynchronously(this, this::boot);
    }

    @Override
    public void onDisable() {
        if (client != null) client.stop();
        if (antiBot != null) antiBot.shutdown();
    }

    private AntiBot antiBot() {
        if (antiBot != null) return antiBot;
        ConfigurationSection a = getConfig().getConfigurationSection("antibot");
        AntiBot.Config c = new AntiBot.Config();
        if (a != null) {
            c.enabled = a.getBoolean("enabled", c.enabled);
            try {
                c.verify = AntiBot.VerifyMode.valueOf(a.getString("verify", "during-attack").toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException e) {
                getLogger().warning("antibot.verify must be always, during-attack or never");
            }
            c.verifyMinRejoinMs = a.getLong("verify-min-rejoin-ms", c.verifyMinRejoinMs);
            c.verifyMaxRejoinMs = a.getLong("verify-max-rejoin-ms", c.verifyMaxRejoinMs);
            c.verifiedExpiryMs = a.getLong("verified-expiry-days", 7) * 86_400_000L;
            c.attackThresholdPerSecond = a.getInt("attack.threshold-logins-per-second", c.attackThresholdPerSecond);
            c.attackCooldownMs = a.getLong("attack.cooldown-seconds", 30) * 1000L;
            c.attackMaxNewPerSecond = a.getInt("attack.max-new-players-per-second", c.attackMaxNewPerSecond);
            c.maxPreLoginGlobal = a.getInt("limits.max-open-handshakes", c.maxPreLoginGlobal);
            c.maxPreLoginPerIp = a.getInt("limits.max-open-handshakes-per-ip", c.maxPreLoginPerIp);
            c.handshakeTimeoutMs = a.getLong("limits.handshake-timeout-ms", c.handshakeTimeoutMs);
            c.maxConnectionsPerIpPer10s = a.getInt("limits.connections-per-ip-per-10s", c.maxConnectionsPerIpPer10s);
            c.maxLoginsPerIpPer10s = a.getInt("limits.logins-per-ip-per-10s", c.maxLoginsPerIpPer10s);
            c.maxPingsPerIpPer10s = a.getInt("limits.pings-per-ip-per-10s", c.maxPingsPerIpPer10s);
            c.maxNamesPerIpPer10m = a.getInt("limits.names-per-ip-per-10m", c.maxNamesPerIpPer10m);
            c.minProtocol = a.getInt("min-protocol", c.minProtocol);
            c.namePattern = a.getString("name-pattern", c.namePattern);
            c.blacklistAfterFails = a.getInt("blacklist.after-fails", c.blacklistAfterFails);
            c.blacklistDurationMs = a.getLong("blacklist.minutes", 10) * 60_000L;
            ConfigurationSection m = a.getConfigurationSection("messages");
            if (m != null) {
                c.msgVerify = m.getString("verify", c.msgVerify);
                c.msgVerifyFailed = m.getString("verify-failed", c.msgVerifyFailed);
                c.msgAttack = m.getString("attack", c.msgAttack);
                c.msgThrottled = m.getString("throttled", c.msgThrottled);
                c.msgInvalidName = m.getString("invalid-name", c.msgInvalidName);
                c.msgOutdated = m.getString("outdated", c.msgOutdated);
                c.msgBlacklisted = m.getString("blacklisted", c.msgBlacklisted);
            }
        }
        if (!c.enabled) return null;
        antiBot = new AntiBot(c, new RelayClient.Log() {
            public void info(String msg) { getLogger().info(msg); }
            public void warn(String msg) { getLogger().warning(msg); }
        }, getDataFolder().toPath().resolve("verified-players.txt"));
        getLogger().info("AntiBot enabled (verify: " + c.verify.name().toLowerCase().replace('_', '-') + ")");
        return antiBot;
    }

    private void boot() {
        FileConfiguration c = getConfig();
        String agentId = c.getString("agent-id", "");
        String secret = c.getString("agent-secret", "");
        String tunnelHost = c.getString("tunnel-host", "");
        int tunnelPort = c.getInt("tunnel-port", 7000);

        if (isBlank(agentId) || isBlank(secret)) {
            getLogger().info("Not linked to a Serververse account yet. Create a server in your dashboard, "
                    + "then run: /relay claim <code>");
            return;
        }
        if (isBlank(tunnelHost)) {
            getLogger().severe("tunnel-host is empty in config.yml - run /relay claim <code> again");
            return;
        }

        RelayClient.Settings s = new RelayClient.Settings();
        s.tunnelHost = tunnelHost;
        s.tunnelPort = tunnelPort;
        s.agentId = agentId;
        s.secret = secret;
        s.localHost = c.getString("local-host", "127.0.0.1");
        int lp = c.getInt("local-port", 0);
        s.localPort = lp > 0 ? lp : getServer().getPort();
        s.proxyProtocol = c.getBoolean("proxy-protocol", false);
        s.antiBot = antiBot();

        RelayClient rc = new RelayClient(s, new RelayClient.Log() {
            public void info(String m) { getLogger().info(m); }
            public void warn(String m) { getLogger().warning(m); }
        });
        client = rc;
        rc.start();
    }

    /**
     * Trades a one-time claim code (made in the dashboard) for this server's credentials.
     * Runs off the main thread; every Bukkit call afterwards is hopped back onto it.
     */
    private void claim(CommandSender sender, String code) {
        String apiUrl = getConfig().getString("api-url", "");
        if (isBlank(apiUrl)) {
            reply(sender, "api-url is empty in plugins/SerververseRelay/config.yml");
            return;
        }
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl.replaceAll("/+$", "") + "/api/v0.3/relay/claim"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MiniJson.obj("code", code)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> body = MiniJson.parseObject(res.body());
            if (res.statusCode() != 200) {
                String why = MiniJson.str(MiniJson.map(body, "error"), "message");
                reply(sender, "Could not link: " + (why == null ? "HTTP " + res.statusCode() : why));
                return;
            }
            Map<String, Object> data = MiniJson.map(body, "data");
            final String agentId = MiniJson.str(data, "agentId");
            final String secret = MiniJson.str(data, "secret");
            final String tunnelHost = MiniJson.str(data, "tunnelHost");
            final int tunnelPort = MiniJson.num(data, "tunnelPort", 7000);
            final String address = MiniJson.str(data, "publicAddress");
            if (isBlank(agentId) || isBlank(secret) || isBlank(tunnelHost)) {
                reply(sender, "Could not link: the API sent an incomplete answer.");
                return;
            }

            getServer().getScheduler().runTask(this, () -> {
                FileConfiguration c = getConfig();
                c.set("agent-id", agentId);
                c.set("agent-secret", secret);
                c.set("tunnel-host", tunnelHost);
                c.set("tunnel-port", tunnelPort);
                saveConfig();
                if (client != null) client.stop();
                client = null;
                sender.sendMessage("[Serververse Relay] Linked! Players can join at " + address);
                getServer().getScheduler().runTaskAsynchronously(this, this::boot);
            });
        } catch (Exception e) {
            reply(sender, "Could not reach the API: " + e.getMessage());
        }
    }

    private void reply(CommandSender sender, String message) {
        getServer().getScheduler().runTask(this, () -> sender.sendMessage("[Serververse Relay] " + message));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        RelayClient rc = client;
        switch (sub) {
            case "status" -> {
                if (rc == null) {
                    sender.sendMessage("[Serververse Relay] Not running. If this server is not linked yet, use /relay claim <code>.");
                } else {
                    sender.sendMessage("[Serververse Relay] State: " + rc.state());
                    sender.sendMessage("[Serververse Relay] Address: " + (rc.publicAddress() == null ? "-" : rc.publicAddress()));
                    sender.sendMessage("[Serververse Relay] Players via relay: " + rc.activeConnections()
                            + " active, " + rc.totalConnections() + " total");
                }
            }
            case "antibot" -> antiBotCommand(sender, args);
            case "reconnect" -> {
                if (rc != null) rc.stop();
                client = null;
                if (antiBot != null) antiBot.shutdown();
                antiBot = null;
                reloadConfig();
                getServer().getScheduler().runTaskAsynchronously(this, this::boot);
                sender.sendMessage("[Serververse Relay] Reconnecting...");
            }
            case "claim" -> {
                if (args.length < 2) {
                    sender.sendMessage("[Serververse Relay] Usage: /relay claim <code>  (create the code in your dashboard)");
                } else {
                    final String code = args[1];
                    sender.sendMessage("[Serververse Relay] Linking...");
                    getServer().getScheduler().runTaskAsynchronously(this, () -> claim(sender, code));
                }
            }
            default -> sender.sendMessage("[Serververse Relay] Usage: /relay <status|reconnect|claim|antibot>");
        }
        return true;
    }

    private void antiBotCommand(CommandSender sender, String[] args) {
        AntiBot ab = antiBot;
        if (ab == null) {
            sender.sendMessage("[Serververse Relay] AntiBot is off (antibot.enabled in config.yml, then /relay reconnect).");
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase() : "stats";
        switch (sub) {
            case "stats" -> sender.sendMessage("[Serververse Relay] AntiBot - " + ab.stats());
            case "unblock" -> {
                if (args.length < 3) {
                    sender.sendMessage("[Serververse Relay] Usage: /relay antibot unblock <ip>");
                } else {
                    sender.sendMessage("[Serververse Relay] " + (ab.unblock(args[2])
                            ? "Unblocked " + args[2] : args[2] + " was not blacklisted"));
                }
            }
            case "clear-verified" -> {
                ab.clearVerified();
                sender.sendMessage("[Serververse Relay] Cleared the verified players list.");
            }
            default -> sender.sendMessage("[Serververse Relay] Usage: /relay antibot <stats|unblock <ip>|clear-verified>");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

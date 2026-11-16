package com.serververse.relay;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
            case "reconnect" -> {
                if (rc != null) rc.stop();
                client = null;
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
            default -> sender.sendMessage("[Serververse Relay] Usage: /relay <status|reconnect|claim>");
        }
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

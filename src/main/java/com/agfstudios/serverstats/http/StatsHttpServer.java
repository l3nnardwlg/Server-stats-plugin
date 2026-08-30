package com.agfstudios.serverstats.http;

import com.agfstudios.serverstats.ServerStatsPlugin;
import com.agfstudios.serverstats.stats.StatsService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class StatsHttpServer {
    private final ServerStatsPlugin plugin;
    private final StatsService stats;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private HttpServer server;

    public StatsHttpServer(ServerStatsPlugin plugin, StatsService stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    public void start() throws IOException {
        String host = plugin.getConfig().getString("http.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("http.port", 8765);
        String apiKey = plugin.getConfig().getString("http.api-key", "CHANGE_ME");
        boolean authRequired = plugin.getConfig().getBoolean("security.require-authentication", true);
        if (authRequired && (apiKey == null || apiKey.isBlank() || apiKey.equals("CHANGE_ME"))) {
            throw new IllegalStateException("Set a secure http.api-key before enabling the API.");
        }

        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/status", exchange -> handle(exchange, true, () -> stats.getStatus()));
        this.server.createContext("/stats", exchange -> handle(exchange, false, () -> stats.getSnapshot()));
        this.server.createContext("/leaderboards", this::handleLeaderboard);
        this.server.createContext("/players", this::handlePlayer);
        this.server.createContext("/health", exchange -> handle(exchange, true, () -> Map.of("status", "ok")));
        this.server.start();
        plugin.getLogger().info("HTTP API listening on " + host + ":" + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handleLeaderboard(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange, false)) return;
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String prefix = "/leaderboards/";
        if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
            send(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        String type = path.substring(prefix.length());
        List<String> valid = List.of("kills", "deaths", "playtime", "blocksPlaced", "blocksBroken");
        if (!valid.contains(type)) {
            send(exchange, 400, Map.of("error", "invalid_leaderboard_type", "allowed", valid));
            return;
        }
        send(exchange, 200, stats.getLeaderboard(type));
    }

    private void handlePlayer(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange, false)) return;
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String prefix = "/players/";
        if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
            send(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        String name = java.net.URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
        var player = stats.getPlayer(name);
        if (player.isEmpty()) {
            send(exchange, 404, Map.of("error", "player_not_found"));
            return;
        }
        send(exchange, 200, player.get());
    }

    private void handle(HttpExchange exchange, boolean statusEndpoint, ResponseSupplier supplier) throws IOException {
        if (!authenticate(exchange, statusEndpoint)) return;
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        try {
            send(exchange, 200, supplier.get());
        } catch (Exception exception) {
            plugin.getLogger().warning("API request failed: " + exception.getMessage());
            send(exchange, 500, Map.of("error", "internal_server_error"));
        }
    }

    private boolean authenticate(HttpExchange exchange, boolean statusEndpoint) throws IOException {
        boolean required = plugin.getConfig().getBoolean("security.require-authentication", true);
        boolean publicStatus = plugin.getConfig().getBoolean("security.allow-unauthenticated-status", false);
        if (!required || (statusEndpoint && publicStatus)) return true;

        String expected = plugin.getConfig().getString("http.api-key", "");
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String provided = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        if (!constantTimeEquals(expected, provided)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            send(exchange, 401, Map.of("error", "unauthorized"));
            return false;
        }
        return true;
    }

    private boolean constantTimeEquals(String expected, String provided) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = provided.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    @FunctionalInterface
    private interface ResponseSupplier {
        Object get() throws Exception;
    }
}

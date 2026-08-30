package com.agfstudios.serverstats;

import com.agfstudios.serverstats.http.StatsHttpServer;
import com.agfstudios.serverstats.stats.StatsService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class ServerStatsPlugin extends JavaPlugin {
    private StatsService statsService;
    private StatsHttpServer httpServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.statsService = new StatsService(this);
        this.statsService.start();
        startHttpServer();
        getLogger().info("ServerStats enabled.");
    }

    @Override
    public void onDisable() {
        stopHttpServer();
        if (statsService != null) {
            statsService.close();
        }
    }

    private void startHttpServer() {
        try {
            this.httpServer = new StatsHttpServer(this, statsService);
            this.httpServer.start();
        } catch (Exception exception) {
            getLogger().severe("Could not start HTTP API: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("serverstats.admin")) {
                sender.sendMessage("§cYou do not have permission to do that.");
                return true;
            }
            reloadConfig();
            stopHttpServer();
            statsService.reloadConfig();
            startHttpServer();
            sender.sendMessage("§aServerStats configuration reloaded.");
            return true;
        }

        sender.sendMessage("§6ServerStats §7- HTTP API is " + (httpServer != null ? "§aonline" : "§coffline") + "§7.");
        sender.sendMessage("§7Use §f/serverstats reload §7to reload configuration.");
        return true;
    }
}

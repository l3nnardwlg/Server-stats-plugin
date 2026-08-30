package com.agfstudios.serverstats.stats;

import com.agfstudios.serverstats.ServerStatsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class StatsService implements Listener, AutoCloseable {
    private final ServerStatsPlugin plugin;
    private final AtomicReference<StatsSnapshot> snapshot = new AtomicReference<>();
    private PersistentStats persistent;
    private Path persistenceFile;
    private Instant countFrom;
    private int taskId = -1;

    public StatsService(ServerStatsPlugin plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        scheduleRefresh();
        refresh();
    }

    public void reloadConfig() {
        String file = plugin.getConfig().getString("stats.persist-file", "stats.json");
        this.persistenceFile = plugin.getDataFolder().toPath().resolve(file).normalize();
        this.countFrom = parseStart(plugin.getConfig().getString("stats.count-from", "1970-01-01T00:00:00Z"));
        this.persistent = PersistentStats.load(persistenceFile);
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            scheduleRefresh();
        }
    }

    private Instant parseStart(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {
            plugin.getLogger().warning("Invalid stats.count-from value; counting immediately.");
            return Instant.EPOCH;
        }
    }

    private void scheduleRefresh() {
        long seconds = Math.max(1L, plugin.getConfig().getLong("stats.refresh-seconds", 5L));
        this.taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::refresh, 0L, seconds * 20L);
    }

    public StatsSnapshot getSnapshot() {
        StatsSnapshot current = snapshot.get();
        if (current == null) {
            refresh();
            current = snapshot.get();
        }
        return current;
    }

    public StatsSnapshot.ServerStatus getStatus() {
        double tps = Bukkit.getTPS().length > 0 ? Bukkit.getTPS()[0] : 0.0;
        String motd = Bukkit.getMotd();
        return new StatsSnapshot.ServerStatus(
                "online",
                Bukkit.getOnlinePlayers().size(),
                Bukkit.getMaxPlayers(),
                Bukkit.getMinecraftVersion(),
                motd,
                Math.round(tps * 100.0) / 100.0,
                Instant.now().toString()
        );
    }

    public List<StatsSnapshot.LeaderboardEntry> getLeaderboard(String type) {
        List<StatsSnapshot.LeaderboardEntry> entries = new ArrayList<>();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            String name = player.getName();
            if (name == null) continue;
            long value = statValue(player, type);
            entries.add(new StatsSnapshot.LeaderboardEntry(0, name, player.getUniqueId().toString(), value));
        }
        entries.sort(Comparator.comparingLong(StatsSnapshot.LeaderboardEntry::value).reversed());
        List<StatsSnapshot.LeaderboardEntry> ranked = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            StatsSnapshot.LeaderboardEntry entry = entries.get(i);
            ranked.add(new StatsSnapshot.LeaderboardEntry(i + 1, entry.name(), entry.uuid(), entry.value()));
        }
        return ranked;
    }

    public Optional<StatsSnapshot.PlayerDetails> getPlayer(String query) {
        OfflinePlayer target = null;
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null && player.getName().equalsIgnoreCase(query)) {
                target = player;
                break;
            }
        }
        if (target == null) return Optional.empty();

        long playTicks = safeStat(target, Statistic.PLAY_ONE_MINUTE);
        long kills = safeStat(target, Statistic.PLAYER_KILLS) + safeStat(target, Statistic.MOB_KILLS);
        return Optional.of(new StatsSnapshot.PlayerDetails(
                target.getName(),
                target.getUniqueId().toString(),
                target.isOnline(),
                roundHours(playTicks),
                kills,
                safeStat(target, Statistic.DEATHS),
                sumMaterialStatistic(target, Statistic.USE_ITEM),
                sumMaterialStatistic(target, Statistic.MINE_BLOCK),
                target.getFirstPlayed() > 0 ? Instant.ofEpochMilli(target.getFirstPlayed()).toString() : null,
                target.getLastPlayed() > 0 ? Instant.ofEpochMilli(target.getLastPlayed()).toString() : null
        ));
    }

    private long statValue(OfflinePlayer player, String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "kills" -> safeStat(player, Statistic.PLAYER_KILLS) + safeStat(player, Statistic.MOB_KILLS);
            case "deaths" -> safeStat(player, Statistic.DEATHS);
            case "playtime" -> safeStat(player, Statistic.PLAY_ONE_MINUTE);
            case "blocksplaced" -> sumMaterialStatistic(player, Statistic.USE_ITEM);
            case "blocksbroken" -> sumMaterialStatistic(player, Statistic.MINE_BLOCK);
            default -> 0L;
        };
    }

    private void refresh() {
        int online = Bukkit.getOnlinePlayers().size();
        if (countingActive() && online > persistent.peakPlayers) {
            persistent.peakPlayers = online;
            saveQuietly();
        }

        OfflinePlayer[] players = Bukkit.getOfflinePlayers();
        long totalPlayTicks = 0;
        long kills = 0;
        long deaths = 0;
        long blocksBroken = 0;
        long blocksPlaced = 0;
        for (OfflinePlayer player : players) {
            totalPlayTicks += safeStat(player, Statistic.PLAY_ONE_MINUTE);
            kills += safeStat(player, Statistic.PLAYER_KILLS) + safeStat(player, Statistic.MOB_KILLS);
            deaths += safeStat(player, Statistic.DEATHS);
            blocksBroken += sumMaterialStatistic(player, Statistic.MINE_BLOCK);
            blocksPlaced += sumMaterialStatistic(player, Statistic.USE_ITEM);
        }

        double totalHours = roundHours(totalPlayTicks);
        snapshot.set(new StatsSnapshot(
                players.length,
                online,
                persistent.peakPlayers,
                totalHours,
                kills,
                deaths,
                blocksPlaced,
                blocksBroken,
                players.length == 0 ? 0.0 : Math.round((totalHours / players.length) * 100.0) / 100.0,
                persistent.pvpKills,
                persistent.mobKills
        ));
    }

    private boolean countingActive() {
        return !Instant.now().isBefore(countFrom);
    }

    private long safeStat(OfflinePlayer player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long sumMaterialStatistic(OfflinePlayer player, Statistic statistic) {
        long total = 0L;
        for (org.bukkit.Material material : org.bukkit.Material.values()) {
            if (!material.isBlock()) continue;
            try {
                total += player.getStatistic(statistic, material);
            } catch (Exception ignored) {
                // Statistic/material pair is not supported.
            }
        }
        return total;
    }

    private double roundHours(long ticks) {
        return Math.round((ticks / 20.0 / 60.0 / 60.0) * 100.0) / 100.0;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!countingActive()) return;
        int online = Bukkit.getOnlinePlayers().size();
        if (online > persistent.peakPlayers) {
            persistent.peakPlayers = online;
            saveQuietly();
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!countingActive()) return;
        persistent.deaths++;
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            persistent.pvpKills++;
        } else {
            persistent.mobKills++;
        }
        saveQuietly();
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!countingActive()) return;
        persistent.blocksPlaced++;
        saveQuietly();
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!countingActive()) return;
        persistent.blocksBroken++;
        saveQuietly();
    }

    private void saveQuietly() {
        try {
            persistent.save(persistenceFile);
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not persist stats: " + exception.getMessage());
        }
    }

    @Override
    public void close() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        saveQuietly();
    }
}

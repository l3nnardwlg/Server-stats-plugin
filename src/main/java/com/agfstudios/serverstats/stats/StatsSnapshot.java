package com.agfstudios.serverstats.stats;

import java.util.List;

public record StatsSnapshot(
        int totalPlayers,
        int onlinePlayers,
        int peakPlayers,
        double totalPlaytimeHours,
        long kills,
        long deaths,
        long blocksPlaced,
        long blocksBroken,
        double averagePlaytimeHours,
        long pvpKills,
        long mobKills
) {
    public record ServerStatus(
            String status,
            int onlinePlayers,
            int maxPlayers,
            String version,
            String motd,
            Double tps,
            String checkedAt
    ) {}

    public record LeaderboardEntry(int rank, String name, String uuid, long value) {}

    public record PlayerDetails(
            String name,
            String uuid,
            boolean online,
            double playtimeHours,
            long kills,
            long deaths,
            long blocksPlaced,
            long blocksBroken,
            String firstJoined,
            String lastJoined
    ) {}

    public record Leaderboard(List<LeaderboardEntry> entries) {}
}

package com.agfstudios.serverstats.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class PersistentStats {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    int peakPlayers;
    long pvpKills;
    long mobKills;
    long deaths;
    long blocksPlaced;
    long blocksBroken;

    static PersistentStats load(Path path) {
        if (!Files.exists(path)) {
            return new PersistentStats();
        }
        try {
            PersistentStats stats = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), PersistentStats.class);
            return stats == null ? new PersistentStats() : stats;
        } catch (Exception ignored) {
            return new PersistentStats();
        }
    }

    synchronized void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
    }
}

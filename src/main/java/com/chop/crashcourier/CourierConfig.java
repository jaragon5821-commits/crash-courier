package com.chop.crashcourier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * Two small files in config/:
 *  - crashcourier.properties: the player's own choice, install id and progress (never overwritten by the pack)
 *  - crashcourier-pack.properties: shipped by the pack (pack id, version, relay address, pack key)
 */
public final class CourierConfig {
    public enum Consent { UNSET, AUTO, ASK, NEVER }

    public static final String PLAYER_FILE = "crashcourier.properties";
    public static final String PACK_FILE = "crashcourier-pack.properties";

    private final Path playerFile;
    private final Properties player = new Properties();

    public final String packId;
    public final String packVersion;
    public final String packKey;
    public final URI relay;

    private CourierConfig(Path configDir, Properties pack) {
        this.playerFile = configDir.resolve(PLAYER_FILE);
        this.packId = pack.getProperty("packId", "").trim();
        this.packVersion = pack.getProperty("packVersion", "").trim();
        this.packKey = pack.getProperty("packKey", "").trim();
        URI uri = null;
        try {
            uri = URI.create(pack.getProperty("relayUrl", "").trim());
            boolean loopback = "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null || !("https".equals(uri.getScheme()) || loopback)) uri = null;
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        this.relay = uri;
    }

    public static CourierConfig load(Path configDir) throws IOException {
        Properties pack = new Properties();
        Path packFile = configDir.resolve(PACK_FILE);
        if (Files.exists(packFile)) {
            try (Reader r = Files.newBufferedReader(packFile, StandardCharsets.UTF_8)) {
                pack.load(r);
            }
        }
        CourierConfig cfg = new CourierConfig(configDir, pack);
        if (Files.exists(cfg.playerFile)) {
            try (Reader r = Files.newBufferedReader(cfg.playerFile, StandardCharsets.UTF_8)) {
                cfg.player.load(r);
            }
        }
        boolean changed = false;
        if (cfg.player.getProperty("installId") == null) {
            cfg.player.setProperty("installId", UUID.randomUUID().toString());
            changed = true;
        }
        if (cfg.player.getProperty("lastScan") == null) {
            // Crashes from before the mod was installed are never sent.
            cfg.player.setProperty("lastScan", Long.toString(System.currentTimeMillis()));
            changed = true;
        }
        if (cfg.player.getProperty("consent") == null) {
            cfg.player.setProperty("consent", "unset");
            changed = true;
        }
        if (changed) cfg.save();
        return cfg;
    }

    public boolean packReady() {
        return !packId.isEmpty() && !packVersion.isEmpty() && !packKey.isEmpty() && relay != null;
    }

    public String installId() {
        return player.getProperty("installId");
    }

    public Consent consent() {
        try {
            return Consent.valueOf(player.getProperty("consent", "unset").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Consent.UNSET;
        }
    }

    public void setConsent(Consent consent) throws IOException {
        player.setProperty("consent", consent.name().toLowerCase(Locale.ROOT));
        save();
    }

    public long lastScan() {
        try {
            return Long.parseLong(player.getProperty("lastScan", "0").trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void setLastScan(long millis) throws IOException {
        player.setProperty("lastScan", Long.toString(millis));
        player.setProperty("retries", "0");
        save();
    }

    public int retries() {
        try {
            return Integer.parseInt(player.getProperty("retries", "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void bumpRetries() throws IOException {
        player.setProperty("retries", Integer.toString(retries() + 1));
        save();
    }

    private void save() throws IOException {
        Files.createDirectories(playerFile.getParent());
        try (Writer w = Files.newBufferedWriter(playerFile, StandardCharsets.UTF_8)) {
            player.store(w, " Crash Courier. consent = auto | ask | never (unset shows the choice screen once).\n"
                + " Source and what is sent: https://github.com/jaragon5821-commits/crash-courier");
        }
    }
}

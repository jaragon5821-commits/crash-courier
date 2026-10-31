package com.chop.crashcourier;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Does the work, with no Minecraft classes, so it runs before other mods finish loading
 * and can be tested on its own. The Forge entry point and the client screens call into this.
 */
public final class Courier {
    static final int MAX_PER_LAUNCH = 3;
    static final int MAX_RETRIES = 3;

    private final Path gameDir;
    private final String side;
    private final String modVersion;
    private final Consumer<String> log;
    private final Redactor redactor;
    private final HttpClient http;

    private volatile CourierConfig config;
    private volatile List<CrashFinder.Crash> pending = List.of();

    public Courier(Path gameDir, String side, String modVersion, Consumer<String> log, Redactor redactor, HttpClient http) {
        this.gameDir = gameDir;
        this.side = side;
        this.modVersion = modVersion;
        this.log = log;
        this.redactor = redactor;
        this.http = http;
    }

    /** Runs on a background thread at startup. */
    public void scan() {
        try {
            config = CourierConfig.load(gameDir.resolve("config"));
            if (!config.packReady()) {
                log.accept("Crash Courier: pack settings missing in config/" + CourierConfig.PACK_FILE + ", nothing will be sent.");
                return;
            }
            List<CrashFinder.Crash> found = new CrashFinder(gameDir).newCrashes(config.lastScan());
            pending = found;
            CourierConfig.Consent consent = config.consent();
            if (found.isEmpty()) return;
            if (consent == CourierConfig.Consent.AUTO) {
                sendPending();
            } else if (consent == CourierConfig.Consent.NEVER || ("server".equals(side) && consent == CourierConfig.Consent.UNSET)) {
                if ("server".equals(side) && consent == CourierConfig.Consent.UNSET) {
                    log.accept("Crash Courier: set consent=auto in config/" + CourierConfig.PLAYER_FILE + " to send server crashes.");
                }
                skipPending();
            }
        } catch (Exception e) {
            log.accept("Crash Courier: scan failed: " + e);
        }
    }

    public boolean needsChoice() {
        return config != null && config.packReady() && config.consent() == CourierConfig.Consent.UNSET;
    }

    public boolean needsAsk() {
        return config != null && config.packReady() && config.consent() == CourierConfig.Consent.ASK && !pending.isEmpty();
    }

    public int pendingCount() {
        return pending.size();
    }

    public void choose(CourierConfig.Consent consent) {
        try {
            config.setConsent(consent);
            if (consent == CourierConfig.Consent.AUTO) sendPendingAsync();
            else if (consent == CourierConfig.Consent.NEVER) skipPending();
        } catch (IOException e) {
            log.accept("Crash Courier: could not save choice: " + e);
        }
    }

    public void sendPendingAsync() {
        Thread t = new Thread(this::sendPending, "Crash Courier upload");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void skipPending() {
        try {
            long latest = config.lastScan();
            for (CrashFinder.Crash c : pending) latest = Math.max(latest, c.modified());
            config.setLastScan(latest);
            pending = List.of();
        } catch (IOException e) {
            log.accept("Crash Courier: could not save progress: " + e);
        }
    }

    public synchronized void sendPending() {
        CrashFinder finder = new CrashFinder(gameDir);
        List<CrashFinder.Crash> list = pending;
        // Only the newest few: a crash loop sends one report per launch, not a flood.
        List<CrashFinder.Crash> toSend = list.size() > MAX_PER_LAUNCH ? list.subList(list.size() - MAX_PER_LAUNCH, list.size()) : list;
        try {
            for (CrashFinder.Crash crash : toSend) {
                Result r = send(redactor.redact(finder.buildText(crash)));
                if (r == Result.RETRY) {
                    config.bumpRetries();
                    if (config.retries() < MAX_RETRIES) {
                        log.accept("Crash Courier: relay unreachable, will try again next launch.");
                        return;
                    }
                    log.accept("Crash Courier: giving up on " + crash.file().getFileName() + " after " + MAX_RETRIES + " tries.");
                }
                config.setLastScan(Math.max(config.lastScan(), crash.modified()));
            }
            skipPending();
        } catch (Exception e) {
            log.accept("Crash Courier: send failed: " + e);
        }
    }

    enum Result { SENT, DROPPED, RETRY }

    Result send(String text) throws IOException, InterruptedException {
        String body = "{"
            + "\"pack\":" + Json.str(config.packId)
            + ",\"packVersion\":" + Json.str(config.packVersion)
            + ",\"installId\":" + Json.str(config.installId())
            + ",\"side\":" + Json.str(side)
            + ",\"modVersion\":" + Json.str(modVersion)
            + ",\"text\":" + Json.str(text)
            + "}";
        HttpRequest req = HttpRequest.newBuilder(config.relay.resolve("/v1/report"))
            .timeout(Duration.ofSeconds(20))
            .header("content-type", "application/json")
            .header("x-pack-key", config.packKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code == 202) {
                log.accept("Crash Courier: crash report sent.");
                return Result.SENT;
            }
            if (code == 429 || code >= 500) return Result.RETRY;
            log.accept("Crash Courier: relay refused the report (" + code + ").");
            return Result.DROPPED;
        } catch (IOException e) {
            return Result.RETRY;
        }
    }
}

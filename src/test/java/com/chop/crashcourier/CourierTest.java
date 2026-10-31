package com.chop.crashcourier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CourierTest {
    @TempDir Path game;
    HttpServer server;
    final List<String> bodies = new ArrayList<>();
    final AtomicInteger status = new AtomicInteger(202);

    @BeforeEach
    void startRelay() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/report", ex -> {
            String key = ex.getRequestHeaders().getFirst("x-pack-key");
            bodies.add(key + "|" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(status.get(), -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopRelay() {
        server.stop(0);
    }

    private void writePack(String relay) throws IOException {
        Path cfg = Files.createDirectories(game.resolve("config"));
        Files.writeString(cfg.resolve(CourierConfig.PACK_FILE),
            "packId=spooktacular\npackVersion=2026.10.31-1\npackKey=spk-test\nrelayUrl=" + relay + "\n");
    }

    private void writePack() throws IOException {
        writePack("https://relay.example");
    }

    private String localRelay() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Path crash(String name, long ageMillis) throws IOException {
        Path dir = Files.createDirectories(game.resolve("crash-reports"));
        Path p = dir.resolve(name);
        Files.writeString(p, RedactorTest.SAMPLE);
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() + ageMillis));
        return p;
    }

    @Test
    void firstRunIgnoresOldCrashesAndWaitsForChoice() throws IOException {
        writePack();
        crash("crash-old-client.txt", -60_000);
        Courier c = new Courier(game, "client", "0.1.0", s -> {}, new Redactor(), HttpClient.newHttpClient());
        c.scan();
        assertTrue(c.needsChoice());
        assertEquals(0, c.pendingCount());
        String props = Files.readString(game.resolve("config").resolve(CourierConfig.PLAYER_FILE));
        assertTrue(props.contains("consent=unset"));
        assertTrue(props.contains("installId="));
    }

    @Test
    void askModeQueuesNewCrash() throws IOException {
        writePack();
        Path cfg = game.resolve("config");
        Files.writeString(cfg.resolve(CourierConfig.PLAYER_FILE), "consent=ask\nlastScan=1\ninstallId=123e4567-e89b-42d3-a456-426614174000\n");
        crash("crash-new-client.txt", 0);
        Courier c = new Courier(game, "client", "0.1.0", s -> {}, new Redactor(), HttpClient.newHttpClient());
        c.scan();
        assertTrue(c.needsAsk());
        c.skipPending();
        assertFalse(c.needsAsk());
    }

    @Test
    void serverWithoutConsentSendsNothing() throws IOException {
        writePack();
        Files.writeString(game.resolve("config").resolve(CourierConfig.PLAYER_FILE), "lastScan=1\n");
        crash("crash-new-server.txt", 0);
        List<String> logs = new ArrayList<>();
        Courier c = new Courier(game, "server", "0.1.0", logs::add, new Redactor(), HttpClient.newHttpClient());
        c.scan();
        assertEquals(0, c.pendingCount());
        assertTrue(logs.stream().anyMatch(s -> s.contains("consent=auto")));
    }

    @Test
    void missingPackFileDoesNothing() {
        List<String> logs = new ArrayList<>();
        Courier c = new Courier(game, "client", "0.1.0", logs::add, new Redactor(), HttpClient.newHttpClient());
        c.scan();
        assertFalse(c.needsChoice());
        assertTrue(logs.get(0).contains("pack settings missing"));
    }

    @Test
    void buildsTextWithLogTailAndNoChat() throws IOException {
        Path logs = Files.createDirectories(game.resolve("logs"));
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (OutputStream gz = new GZIPOutputStream(raw)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) sb.append("line ").append(i).append('\n');
            sb.append("[CHAT] <Friend> secret\n");
            gz.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        Files.write(logs.resolve("2026-10-31-1.log.gz"), raw.toByteArray());
        Path p = crash("crash-x-client.txt", 0);
        String text = new CrashFinder(game).buildText(new CrashFinder.Crash(p, 0));
        assertTrue(text.startsWith("---- Minecraft Crash Report ----"));
        assertTrue(text.contains("line 499"));
        assertFalse(text.contains("line 100\n"));
        assertFalse(text.contains("secret"));
    }

    @Test
    void trimsHugeThreadDump() {
        String dump = "-- Thread Dump --\n" + "x".repeat(100_000) + "\n-- System Details --\nJava";
        String out = CrashFinder.trimThreadDump("head\n" + dump);
        assertTrue(out.length() < 50_000);
        assertTrue(out.contains("-- System Details --"));
    }

    @Test
    void autoSendsScrubbedReportOnce() throws IOException {
        writePack(localRelay());
        Files.writeString(game.resolve("config").resolve(CourierConfig.PLAYER_FILE),
            "consent=auto\nlastScan=1\ninstallId=123e4567-e89b-42d3-a456-426614174000\n");
        crash("crash-new-client.txt", 0);
        new Courier(game, "client", "0.1.0", s -> {}, new Redactor("Loreichop"), HttpClient.newHttpClient()).scan();
        assertEquals(1, bodies.size());
        String body = bodies.get(0);
        assertTrue(body.startsWith("spk-test|"));
        assertTrue(body.contains("\"pack\":\"spooktacular\""));
        assertTrue(body.contains("\"side\":\"client\""));
        assertFalse(body.contains("Loreichop"));
        assertFalse(body.contains("JoshSecret"));

        new Courier(game, "client", "0.1.0", s -> {}, new Redactor(), HttpClient.newHttpClient()).scan();
        assertEquals(1, bodies.size(), "already-sent crash is not sent again");
    }

    @Test
    void relayDownRetriesNextLaunchThenGivesUp() throws IOException {
        writePack(localRelay());
        Files.writeString(game.resolve("config").resolve(CourierConfig.PLAYER_FILE),
            "consent=auto\nlastScan=1\ninstallId=123e4567-e89b-42d3-a456-426614174000\n");
        crash("crash-new-client.txt", 0);
        status.set(503);
        for (int i = 0; i < 4; i++) {
            new Courier(game, "client", "0.1.0", s -> {}, new Redactor(), HttpClient.newHttpClient()).scan();
        }
        assertEquals(3, bodies.size());
    }

    @Test
    void jsonEscapes() {
        assertEquals("\"a\\\"b\\n\\u0001\"", Json.str("a\"b\n\u0001"));
    }
}

package com.chop.crashcourier;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Finds crash files newer than the last scan and builds the text that gets sent. */
public final class CrashFinder {
    static final int MAX_CRASH_CHARS = 180_000;
    static final int MAX_TOTAL_CHARS = 240_000;
    static final int THREAD_DUMP_CHARS = 40_000;
    static final int LOG_TAIL_LINES = 300;

    public record Crash(Path file, long modified) {}

    private final Path gameDir;

    public CrashFinder(Path gameDir) {
        this.gameDir = gameDir;
    }

    public List<Crash> newCrashes(long since) throws IOException {
        List<Crash> found = new ArrayList<>();
        collect(gameDir.resolve("crash-reports"), "*.txt", since, found);
        collect(gameDir, "hs_err_pid*.log", since, found);
        found.sort(Comparator.comparingLong(Crash::modified));
        return found;
    }

    private static void collect(Path dir, String glob, long since, List<Crash> out) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) {
                long t = Files.getLastModifiedTime(p).toMillis();
                if (t > since && Files.isRegularFile(p)) out.add(new Crash(p, t));
            }
        }
    }

    /** Crash text (thread dumps trimmed) plus the tail of the previous session's log. */
    public String buildText(Crash crash) throws IOException {
        String crashText = trimThreadDump(readLenient(crash.file()));
        if (crashText.length() > MAX_CRASH_CHARS) {
            crashText = crashText.substring(0, MAX_CRASH_CHARS) + "\n[Crash Courier: cut at " + MAX_CRASH_CHARS + " characters]";
        }
        StringBuilder sb = new StringBuilder(crashText);
        String tail = previousLogTail();
        if (!tail.isEmpty()) {
            sb.append("\n\n---- Crash Courier: last ").append(LOG_TAIL_LINES).append(" lines of the previous session log ----\n");
            int room = MAX_TOTAL_CHARS - sb.length();
            sb.append(tail.length() > room ? tail.substring(tail.length() - Math.max(0, room)) : tail);
        }
        return sb.toString();
    }

    static String trimThreadDump(String text) {
        int start = text.indexOf("-- Thread Dump --");
        if (start < 0) return text;
        int end = text.indexOf("\n-- ", start + 1);
        if (end < 0) end = text.length();
        if (end - start <= THREAD_DUMP_CHARS) return text;
        return text.substring(0, start + THREAD_DUMP_CHARS) + "\n[Crash Courier: thread dump trimmed]\n" + text.substring(end);
    }

    /** At startup log4j has already rolled the previous session's latest.log into the newest .log.gz. */
    String previousLogTail() throws IOException {
        Path logs = gameDir.resolve("logs");
        if (!Files.isDirectory(logs)) return "";
        Path newest = null;
        long newestTime = Long.MIN_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(logs, "*.log.gz")) {
            for (Path p : ds) {
                long t = Files.getLastModifiedTime(p).toMillis();
                if (t > newestTime) {
                    newest = p;
                    newestTime = t;
                }
            }
        }
        if (newest == null) return "";
        Deque<String> lines = new ArrayDeque<>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(newest));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("[CHAT]")) continue;
                lines.addLast(line);
                if (lines.size() > LOG_TAIL_LINES) lines.removeFirst();
            }
        }
        return String.join("\n", lines);
    }

    static String readLenient(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

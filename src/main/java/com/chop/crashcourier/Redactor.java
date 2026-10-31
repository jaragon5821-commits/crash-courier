package com.chop.crashcourier;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrubs identity from crash text on the player's PC, before anything is sent.
 * The relay runs the same rules again as a second pass.
 */
public final class Redactor {
    private static final Pattern CHAT = Pattern.compile("\\[CHAT\\]");
    private static final Pattern WIN_HOME = Pattern.compile("([A-Za-z]:[\\\\/]+Users[\\\\/]+)[^\\\\/\\r\\n]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NIX_HOME = Pattern.compile("(/(?:home|Users)/)[^/\\s]+");
    private static final Pattern ARGS = Pattern.compile("(--(?:username|uuid|xuid|clientId|accessToken|session)\\s+)\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_USER = Pattern.compile("(Setting user:\\s*)\\S+");
    private static final Pattern TOKENS = Pattern.compile("((?:access_?token|session(?:_?id)?|token)\\s*[=:]\\s*)[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern JWT = Pattern.compile("\\beyJ[\\w-]{10,}\\.[\\w-]{10,}\\.[\\w-]{10,}");
    private static final Pattern CONNECTING = Pattern.compile("(Connecting to\\s+)[^\\s,]+");
    private static final Pattern EMAIL = Pattern.compile("(?<![\\w.+-])[\\w.+-]{1,64}@[\\w-]+\\.[\\w.-]+");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(\\d{1,3}(?:\\.\\d{1,3}){3})(?![\\w.])");
    private static final Pattern VERSIONISH = Pattern.compile("(version|java|jdk|jre|build|forge|minecraft|mc|v)\\s*[:=]?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV6 = Pattern.compile("(?<![\\w:])[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}(?![\\w:])", Pattern.CASE_INSENSITIVE);

    private final List<String> literals = new ArrayList<>();

    /** @param names exact strings to blank out, such as the Windows account and Minecraft username */
    public Redactor(String... names) {
        for (String n : names) {
            if (n != null && n.trim().length() >= 3) literals.add(n.trim());
        }
    }

    public String redact(String text) {
        StringBuilder kept = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (CHAT.matcher(line).find()) continue;
            kept.append(line).append('\n');
        }
        String out = kept.length() > 0 ? kept.substring(0, kept.length() - 1) : "";

        for (String lit : literals) {
            out = Pattern.compile(Pattern.quote(lit), Pattern.CASE_INSENSITIVE).matcher(out).replaceAll("<name>");
        }
        out = WIN_HOME.matcher(out).replaceAll("$1<user>");
        out = NIX_HOME.matcher(out).replaceAll("$1<user>");
        out = ARGS.matcher(out).replaceAll("$1<redacted>");
        out = SET_USER.matcher(out).replaceAll("$1<player>");
        out = TOKENS.matcher(out).replaceAll("$1<redacted>");
        out = JWT.matcher(out).replaceAll("<jwt>");
        out = CONNECTING.matcher(out).replaceAll("$1<server>");
        out = EMAIL.matcher(out).replaceAll("<email>");
        out = replaceIpv4(out);
        out = replaceIpv6(out);
        return out;
    }

    private static String replaceIpv4(String in) {
        Matcher m = IPV4.matcher(in);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ip = m.group(1);
            boolean valid = true;
            for (String part : ip.split("\\.")) {
                if (Integer.parseInt(part) > 255) valid = false;
            }
            String before = in.substring(Math.max(0, m.start() - 16), m.start());
            boolean version = VERSIONISH.matcher(before).find();
            m.appendReplacement(sb, Matcher.quoteReplacement(valid && !version ? "<ip>" : ip));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceIpv6(String in) {
        Matcher m = IPV6.matcher(in);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String hit = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(looksLikeIpv6(hit) ? "<ip6>" : hit));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** At least three hex groups, and either seven colons or a "::" (so clock times like 12:00:01 are kept). */
    static boolean looksLikeIpv6(String s) {
        long groups = java.util.Arrays.stream(s.split(":")).filter(g -> !g.isEmpty()).count();
        long colons = s.chars().filter(c -> c == ':').count();
        return groups >= 3 && (colons == 7 || s.contains("::"));
    }

    /** Pulls --username out of the launch command, so the literal name is scrubbed everywhere. */
    public static String usernameFromCommand(String command) {
        if (command == null) return null;
        Matcher m = Pattern.compile("--username\\s+(\\S+)").matcher(command);
        return m.find() ? m.group(1) : null;
    }
}

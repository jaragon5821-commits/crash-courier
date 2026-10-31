package com.chop.crashcourier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedactorTest {
    static final String SAMPLE = String.join("\n",
        "---- Minecraft Crash Report ----",
        "Description: Rendering entity in world",
        "java.lang.NullPointerException: boom",
        "\tat com.example.Renderer.render(Renderer.java:42)",
        "\tJava Version: 17.0.8.1, Eclipse Adoptium",
        "\tJVM Flags: C:\\Users\\JoshSecret\\AppData\\Roaming\\PrismLauncher",
        "\tLaunched Version: forge-47.4.26",
        "mail josh.real@example.com",
        "[12:00:01] [Render thread/INFO]: Setting user: Loreichop",
        "[12:00:02] [Render thread/INFO]: Connecting to play.example.net, 25565",
        "[12:00:03] [Render thread/INFO]: [CHAT] <Kaitlyn_28> my base is at 100 64 200",
        "[12:00:04] [Netty/INFO]: /203.0.113.7:51234 logged in",
        "ipv6 2001:db8:85a3::8a2e:370:7334 here",
        "--accessToken abc.def.ghi --uuid 0123abcd",
        "seen by SomeAccount in chat");

    @Test
    void scrubsIdentityAndKeepsVersions() {
        String out = new Redactor("SomeAccount", "Loreichop").redact(SAMPLE);
        for (String leak : new String[] {"JoshSecret", "josh.real@", "Loreichop", "play.example.net", "Kaitlyn_28",
                "203.0.113.7", "2001:db8", "abc.def.ghi", "0123abcd", "SomeAccount"}) {
            assertFalse(out.contains(leak), "leaked " + leak);
        }
        assertTrue(out.contains("17.0.8.1"));
        assertTrue(out.contains("forge-47.4.26"));
        assertTrue(out.contains("NullPointerException"));
        assertTrue(out.contains("Renderer.java:42"));
        assertTrue(out.contains("[12:00:04]"), "clock times kept");
    }

    @Test
    void readsUsernameFromLaunchCommand() {
        assertTrue("Loreichop".equals(Redactor.usernameFromCommand("cpw.mods.bootstraplauncher.BootstrapLauncher --username Loreichop --version 1.20.1")));
    }

    @Test
    void ignoresShortNames() {
        assertTrue(new Redactor("ab").redact("ab cd").contains("ab"));
    }
}

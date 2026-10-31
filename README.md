# Crash Courier

A small Forge 1.20.1 mod for Chop's modpacks. When the game crashes, the next launch can send a
scrubbed copy of the crash report to the pack owner, so bugs get fixed without players digging
through log files. Nothing is sent unless the player opted in.

## What the player sees

On first launch, one screen with three choices: **Send automatically**, **Ask me each time**, or
**Never**. The choice is saved in `config/crashcourier.properties` (`consent = auto | ask | never`)
and can be changed any time. With "ask", a crash brings up a Send / Don't send screen on the next launch.

Dedicated servers have no screen: they send only when `consent=auto` is set in that file.

## What is sent, and only after a crash

- The crash report (`crash-reports/*.txt`) or Java fatal error log (`hs_err_pid*.log`) that is newer than the last check. Huge thread dumps are trimmed.
- The last 300 lines of the previous session's log, with every chat line removed.
- The pack id and version, this mod's version, client or server, and a random install id.

Before sending, the mod removes the Windows account name and home-folder paths, the Minecraft
username, email addresses, IPv4 and IPv6 addresses, server addresses, and login or session tokens.
The relay scrubs again. Reports are kept privately for 60 days.

Never sent: worlds, screenshots, other files, or anything when the game did not crash.
Crashes from before the mod was installed are ignored. A crash loop sends at most 3 reports per launch.

## Pack setup

The pack ships `config/crashcourier-pack.properties`:

```properties
packId=spooktacular
packVersion=2026.10.31-1
packKey=<public pack key>
relayUrl=https://<relay>.workers.dev
```

Without this file the mod does nothing. `packVersion` is bumped with each pack release.
The pack key is public; it only filters junk at the relay. The mod holds no secrets.

## Build

GitHub Actions builds every push (`gradle build`, Java 17, Gradle 8.8). Tagging `v*` attaches the jar to a release.

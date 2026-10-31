package com.chop.crashcourier;

import com.mojang.logging.LogUtils;
import java.net.http.HttpClient;
import java.time.Duration;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

@Mod(CrashCourier.MODID)
public final class CrashCourier {
    public static final String MODID = "crashcourier";
    private static final Logger LOG = LogUtils.getLogger();

    static Courier courier;

    public CrashCourier() {
        boolean client = FMLEnvironment.dist == Dist.CLIENT;
        String version = ModLoadingContext.get().getActiveContainer().getModInfo().getVersion().toString();
        Redactor redactor = new Redactor(
            System.getProperty("user.name"),
            Redactor.usernameFromCommand(System.getProperty("sun.java.command")));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        courier = new Courier(FMLPaths.GAMEDIR.get(), client ? "client" : "server", version, LOG::info, redactor, http);

        // Scan first, on its own thread, so a later mod failing to load does not stop it.
        Thread scan = new Thread(courier::scan, "Crash Courier scan");
        scan.setDaemon(true);
        scan.start();

        if (client) ClientHooks.register();
    }
}

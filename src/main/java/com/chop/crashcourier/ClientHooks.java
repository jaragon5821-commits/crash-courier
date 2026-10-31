package com.chop.crashcourier;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

/** Shows the one-time choice screen, or the "send last crash?" screen, in front of the title menu. */
final class ClientHooks {
    private static boolean shown;

    private ClientHooks() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(ClientHooks::onScreenOpening);
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (shown || !(event.getNewScreen() instanceof TitleScreen title)) return;
        Courier courier = CrashCourier.courier;
        if (courier == null) return;
        if (courier.needsChoice()) {
            shown = true;
            event.setNewScreen(new CourierScreen(title, CourierScreen.Mode.CHOICE, courier));
        } else if (courier.needsAsk()) {
            shown = true;
            event.setNewScreen(new CourierScreen(title, CourierScreen.Mode.ASK, courier));
        }
    }
}

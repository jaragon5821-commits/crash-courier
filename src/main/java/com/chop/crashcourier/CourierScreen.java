package com.chop.crashcourier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class CourierScreen extends Screen {
    enum Mode { CHOICE, ASK }

    private static final String CHOICE_TEXT =
        "If your game crashes, this pack can send a cleaned-up copy of the crash report to Josh so he can fix it.\n\n"
        + "Sent: the crash report, the last 300 lines of the game log (chat removed), the pack version, and the PC info "
        + "already in crash reports. Your Minecraft name, Windows name, IP address and logins are scrubbed first. "
        + "Nothing is sent unless the game crashed.\n\n"
        + "You can change this any time in config/crashcourier.properties.";

    private final Screen parent;
    private final Mode mode;
    private final Courier courier;
    private MultiLineLabel body = MultiLineLabel.EMPTY;

    CourierScreen(Screen parent, Mode mode, Courier courier) {
        super(Component.literal(mode == Mode.CHOICE ? "Crash reports" : "Your game crashed last time"));
        this.parent = parent;
        this.mode = mode;
        this.courier = courier;
    }

    @Override
    protected void init() {
        String text = mode == Mode.CHOICE
            ? CHOICE_TEXT
            : "Send the crash report" + (courier.pendingCount() > 1 ? "s" : "") + " to Josh so he can fix it? "
                + "Names, IPs and chat are scrubbed first.";
        body = MultiLineLabel.create(font, Component.literal(text), Math.min(360, width - 40));
        int y = height / 2 + 40;
        if (mode == Mode.CHOICE) {
            int w = 120;
            int x = width / 2 - (w * 3 + 16) / 2;
            addRenderableWidget(Button.builder(Component.literal("Send automatically"), b -> pick(CourierConfig.Consent.AUTO)).bounds(x, y, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Ask me each time"), b -> pick(CourierConfig.Consent.ASK)).bounds(x + w + 8, y, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Never"), b -> pick(CourierConfig.Consent.NEVER)).bounds(x + 2 * (w + 8), y, w, 20).build());
        } else {
            int w = 100;
            int x = width / 2 - (w * 2 + 8) / 2;
            addRenderableWidget(Button.builder(Component.literal("Send"), b -> {
                courier.sendPendingAsync();
                close();
            }).bounds(x, y, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Don't send"), b -> {
                courier.skipPending();
                close();
            }).bounds(x + w + 8, y, w, 20).build());
        }
    }

    private void pick(CourierConfig.Consent consent) {
        courier.choose(consent);
        close();
    }

    private void close() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return mode == Mode.ASK;
    }

    @Override
    public void onClose() {
        if (mode == Mode.ASK) courier.skipPending();
        close();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, height / 2 - 80, 0xFF9B3D);
        body.renderCentered(g, width / 2, height / 2 - 60);
        super.render(g, mouseX, mouseY, partialTick);
    }
}

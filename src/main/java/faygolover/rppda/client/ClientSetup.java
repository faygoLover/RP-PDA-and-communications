package faygolover.rppda.client;

import com.mojang.blaze3d.platform.InputConstants;
import faygolover.rpcore.client.hud.Anchor;
import faygolover.rpcore.client.hud.HudElement;
import faygolover.rpcore.client.hud.HudRegistry;
import faygolover.rppda.RpPda;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Клиент: клавиша КПК, значок непрочитанного в общем HUD RP Core. */
public final class ClientSetup {
    private ClientSetup() {}

    public static final KeyMapping OPEN = new KeyMapping("key.rppda.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y,
            "key.categories.rppda");

    public static void init(IEventBus modBus) {
        modBus.addListener(ClientSetup::setup);
        modBus.addListener(ClientSetup::keys);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onLogout);
        if (Boolean.getBoolean("rppda.devshot")) DevShots.init();
    }

    private static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> HudRegistry.register(new UnreadHud()));
    }

    private static void keys(RegisterKeyMappingsEvent e) {
        e.register(OPEN);
    }

    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (mc.screen == null && mc.player != null) ClientPda.send("open", new CompoundTag());
        }
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        ClientPda.reset();
    }

    /** Значок КПК с числом непрочитанного (виден, только когда есть непрочитанное). */
    static final class UnreadHud implements HudElement {
        @Override
        public ResourceLocation id() {
            return new ResourceLocation(RpPda.MODID, "unread");
        }

        @Override
        public Component title() {
            return Component.translatable("rppda.hud.unread");
        }

        @Override
        public int width() {
            return 34;
        }

        @Override
        public int height() {
            return 16;
        }

        @Override
        public Anchor defaultAnchor() {
            return Anchor.TOP_RIGHT;
        }

        @Override
        public int defaultX() {
            return -4;
        }

        @Override
        public int defaultY() {
            return 4;
        }

        @Override
        public void render(GuiGraphics g, float partialTick, boolean preview) {
            int n = preview ? 3 : ClientPda.unread;
            if (n <= 0 || !preview && !ClientPda.hasActive) return;
            boolean blink = (System.currentTimeMillis() / 600) % 2 == 0;
            Ui.icon(g, Ui.Icon.PDA, 0, 0, blink ? 0xFFFFFFFF : 0xFF9FD0FF);
            String s = n > 99 ? "99+" : Integer.toString(n);
            var font = Minecraft.getInstance().font;
            int w = font.width(s) + 4;
            g.fill(14, 3, 14 + w, 13, Ui.RED);
            g.drawString(font, s, 16, 4, 0xFFFFFFFF, false);
        }
    }
}

package faygolover.rppda.client;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** Клиент КПК: принимает ответы сервера, держит счётчик непрочитанного и открывает окно. */
public final class ClientPda {
    private ClientPda() {}

    /** Метка строк КПК в чате игры: голубая полоска слева и голубой фон (миксин чата). */
    public static final GuiMessageTag TAG = new GuiMessageTag(0x4FA3FF, null, Component.translatable("rppda.chat.tag"), "RP PDA");

    static int unread;
    static boolean hasActive;

    public static void receive(String op, CompoundTag d) {
        Minecraft mc = Minecraft.getInstance();
        switch (op) {
            case "unread" -> {
                unread = d.getInt("n");
                hasActive = d.getBoolean("has");
            }
            case "line" -> {
                Component c = Component.Serializer.fromJson(d.getString("c"));
                if (c != null) mc.gui.getChat().addMessage(c, null, TAG);
            }
            case "avatars" -> Avatars.receive(d);
            case "close" -> {
                if (mc.screen instanceof PdaScreen) mc.setScreen(null);
            }
            case "register", "locked", "state", "hack", "cipher" -> {
                PdaScreen s = mc.screen instanceof PdaScreen p ? p : null;
                if (s == null) {
                    s = new PdaScreen();
                    mc.setScreen(s);
                }
                s.receive(op, d);
            }
            default -> {
                if (mc.screen instanceof PdaScreen p) p.receive(op, d);
            }
        }
    }

    /** Действие серверу (без соединения — ничего: окно могли открыть в меню). */
    public static void send(String op, CompoundTag data) {
        if (Minecraft.getInstance().getConnection() != null) faygolover.rppda.network.Net.toServer(op, data);
    }

    static void reset() {
        unread = 0;
        hasActive = false;
        Avatars.reset(true);
    }
}

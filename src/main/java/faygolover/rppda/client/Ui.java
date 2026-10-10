package faygolover.rppda.client;

import com.mojang.blaze3d.systems.RenderSystem;
import faygolover.rppda.RpPda;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

/** Оформление КПК: цвета, значки атласа {@code textures/gui/icons.png}, кнопки-значки, аватары, время. */
public final class Ui {
    private Ui() {}

    public static final ResourceLocation ICONS = new ResourceLocation(RpPda.MODID, "textures/gui/icons.png");
    private static final int ATLAS_W = 128, ATLAS_H = 80;

    public static final int BODY = 0xFF23272E, BODY_LIGHT = 0xFF3A404A, BODY_DARK = 0xFF15181D;
    public static final int SCREEN = 0xFF0E1621, PANEL = 0xFF17212B, ROW_HOVER = 0x22FFFFFF;
    public static final int TEXT = 0xFFE6EDF3, DIM = 0xFF8B98A5, ACCENT = 0xFF4FA3FF, RED = 0xFFE5534B,
            GREEN = 0xFF57C26A, GOLD = 0xFFE0B341;
    public static final int OWN = 0xFF2B5278, OTHER = 0xFF1F2A36, SYSTEM_BODY = 0xFF2E2224;

    /** Номер значка в атласе — тот же порядок, что в {@code scripts/gen_icons.py}. */
    public enum Icon {
        CHATS, CONTACTS, PROFILE, LOCK, UNLOCK, CLOSE, BACK, SEND,
        PLUS, GROUP, CHANNEL, BELL, PDA_ONLY, MUTE, MEMBERS, LEAVE,
        KICK, CROWN, REPLY, TRASH, COPY, KEY, SILENT, SOUND,
        ACCOUNTS, LOG, SWITCH, GIVE, TELEPORT, EDIT, BLOCK, CHECK,
        SEARCH, MESSAGE, PDA, REFRESH, PERSON_ADD, SYSTEM, GLOBE, EYE
    }

    public static void icon(GuiGraphics g, Icon icon, int x, int y, int color) {
        int n = icon.ordinal();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f,
                ((color >>> 24) & 255) / 255f);
        g.blit(ICONS, x, y, (n % 8) * 16, (n / 8) * 16, 16, 16, ATLAS_W, ATLAS_H);
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    /** Значок поменьше (масштаб). */
    public static void icon(GuiGraphics g, Icon icon, int x, int y, int size, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size / 16f, size / 16f, 1);
        icon(g, icon, 0, 0, color);
        g.pose().popPose();
    }

    /** Кнопка-значок. Название — подсказкой при наведении дольше секунды. */
    public static class IconButton extends Button {
        public Icon icon;
        public int color = TEXT;
        @Nullable
        private final Component label;

        public IconButton(int x, int y, int w, int h, Icon icon, Component tooltip, @Nullable Component label, OnPress press) {
            super(x, y, w, h, tooltip, press, DEFAULT_NARRATION);
            this.icon = icon;
            this.label = label;
            setTooltip(Tooltip.create(tooltip));
            setTooltipDelay(1000);
        }

        public IconButton(int x, int y, Icon icon, Component tooltip, OnPress press) {
            this(x, y, 18, 18, icon, tooltip, null, press);
        }

        public void retip(Component tooltip) {
            setMessage(tooltip);
            setTooltip(Tooltip.create(tooltip));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            if (isHoveredOrFocused() && active) g.fill(getX(), getY(), getX() + width, getY() + height, 0x33FFFFFF);
            if (label != null) g.fill(getX(), getY(), getX() + width, getY() + height, isHovered() ? 0xFF2F6EA8 : 0xFF24557F);
            int c = !active ? 0xFF55606B : isHoveredOrFocused() ? 0xFFFFFFFF : color;
            Font font = Minecraft.getInstance().font;
            if (label == null) {
                icon(g, icon, getX() + (width - 16) / 2, getY() + (height - 16) / 2, c);
            } else {
                int tw = 16 + 4 + font.width(label);
                int x0 = getX() + (width - tw) / 2;
                icon(g, icon, x0, getY() + (height - 16) / 2, c);
                g.drawString(font, label, x0 + 20, getY() + (height - 8) / 2, c, false);
            }
        }
    }

    /** Аватар: лицо персонажа, если игрок в сети; иначе буква на цветном квадрате. Система — звезда. */
    public static void avatar(GuiGraphics g, int x, int y, int size, @Nullable UUID owner, String name, int id) {
        if (id == 0 && owner == null) {
            g.fill(x, y, x + size, y + size, 0xFF8E2A2A);
            icon(g, Icon.SYSTEM, x + 1, y + 1, size - 2, 0xFFFFFFFF);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (owner != null && mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(owner);
            if (info != null) {
                PlayerFaceRenderer.draw(g, info.getSkinLocation(), x, y, size);
                return;
            }
        }
        int hue = Math.floorMod(id * 2654435761L >>> 8, 360);
        g.fill(x, y, x + size, y + size, 0xFF000000 | hsv(hue, 0.45f, 0.62f));
        String letter = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase();
        Font font = mc.font;
        float scale = size / 16f;
        g.pose().pushPose();
        g.pose().translate(x + size / 2f, y + size / 2f, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, letter, -font.width(letter) / 2, -4, 0xFFFFFFFF, false);
        g.pose().popPose();
    }

    private static int hsv(int h, float s, float v) {
        float c = v * s, xx = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = v - c;
        float r, gg, b;
        if (h < 60) { r = c; gg = xx; b = 0; }
        else if (h < 120) { r = xx; gg = c; b = 0; }
        else if (h < 180) { r = 0; gg = c; b = xx; }
        else if (h < 240) { r = 0; gg = xx; b = c; }
        else if (h < 300) { r = xx; gg = 0; b = c; }
        else { r = c; gg = 0; b = xx; }
        return ((int) ((r + m) * 255) << 16) | ((int) ((gg + m) * 255) << 8) | (int) ((b + m) * 255);
    }

    public static String time(long ms) {
        if (ms <= 0) return "";
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(ms);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(today ? "HH:mm" : "dd.MM").format(new Date(ms));
    }

    public static String clock(long ms) {
        return new SimpleDateFormat("HH:mm").format(new Date(ms));
    }

    public static String day(long ms) {
        return new SimpleDateFormat("dd.MM.yyyy").format(new Date(ms));
    }

    public static String formatId(int id) {
        String s = String.format("%08d", id);
        return s.substring(0, 4) + " " + s.substring(4);
    }

    /** Обрезать строку до ширины с «…». */
    public static String fit(Font font, String s, int w) {
        if (font.width(s) <= w) return s;
        return font.plainSubstrByWidth(s, Math.max(0, w - font.width("…"))) + "…";
    }
}

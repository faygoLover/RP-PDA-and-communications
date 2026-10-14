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
    /** Ретро: попиксельные значки 12×12 без сглаживания (scripts/gen_retro_icons.py). */
    public static final ResourceLocation ICONS_RETRO = new ResourceLocation(RpPda.MODID, "textures/gui/icons_retro.png");
    private static final int ATLAS_W = 128, ATLAS_H = 96;

    /** Вид окна: современный или ретро (80-е, люминофор). Ставит открытое окно КПК ({@link #theme}). */
    public static boolean retro;
    /** Экран с кинескопом (свечение, развёртка); у ЖК-экрана — нет. */
    public static boolean crt = true;
    /** Цвет акцента современного вида (для полоски под экраном и т. п.). */
    public static int ACCENT_DARK;

    // Цвета текущего вида (меняет {@link #theme}).
    public static int BODY, BODY_LIGHT, BODY_DARK, SCREEN, PANEL, ROW_HOVER, TEXT, DIM, ACCENT, RED, GREEN, GOLD,
            OWN, OTHER, SYSTEM_BODY, SEP, BAR, TIME, QUOTE, HINT, TOAST_BG, TILE_GLOBE, TILE_GROUP, TILE_INVITE, DATE_BG;

    public static void theme(boolean retroStyle, boolean system) {
        theme(retroStyle, system, "green", "blue");
    }

    private static final java.util.Map<String, int[]> SCREENS = java.util.Map.of(
            // фон, текст, тусклый, средний, панель
            "green", new int[]{0xFF061408, 0xFF78FF8C, 0xFF2E8C40, 0xFF54C868, 0xFF0C2410},
            "white", new int[]{0xFF0A0C10, 0xFFE8EEF6, 0xFF767E8C, 0xFFBAC2D0, 0xFF161A22},
            "blue", new int[]{0xFF040E18, 0xFF6ED2FF, 0xFF286E96, 0xFF50AADC, 0xFF0A1C2C},
            "lcd", new int[]{0xFF98A270, 0xFF1E2816, 0xFF5C6842, 0xFF384428, 0xFF8A9464},
            "synth", new int[]{0xFF160626, 0xFFFF78D6, 0xFF883A96, 0xFF5AE2FF, 0xFF24103A},
            "amber", new int[]{0xFF180C02, 0xFFFFB840, 0xFF965C1C, 0xFFD28C2C, 0xFF2A1606});
    private static final java.util.Map<String, Integer> ACCENTS = java.util.Map.of(
            "blue", 0xFF4FA3FF, "green", 0xFF4FD07A, "orange", 0xFFFF9A3C, "violet", 0xFFA77BFF, "neon", 0xFFFF4FC8);

    static {
        // После таблиц палитр: theme() ими пользуется.
        theme(false, false);
    }

    public static int[] screenColors(String id) {
        return SCREENS.getOrDefault(id, SCREENS.get("green"));
    }

    public static int accentColor(String id) {
        return ACCENTS.getOrDefault(id, 0xFF4FA3FF);
    }

    private static int mix(int a, int b, float k) {
        int r = (int) (((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
        int g = (int) (((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
        int bl = (int) ((a & 255) * (1 - k) + (b & 255) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * Цвета вида. Ретро: экран {@code screen} (у системного КПК — всегда янтарный); «красный/жёлтый/зелёный» —
     * оттенки его же. Современный: акцент {@code accent}.
     */
    public static void theme(boolean retroStyle, boolean system, String screen, String accent) {
        retro = retroStyle;
        crt = true;
        if (!retroStyle) {
            BODY = 0xFF23272E; BODY_LIGHT = 0xFF3A404A; BODY_DARK = 0xFF15181D;
            SCREEN = 0xFF0E1621; PANEL = 0xFF17212B; ROW_HOVER = 0x22FFFFFF;
            TEXT = 0xFFE6EDF3; DIM = 0xFF8B98A5; ACCENT = 0xFF4FA3FF; RED = 0xFFE5534B; GREEN = 0xFF57C26A; GOLD = 0xFFE0B341;
            OWN = 0xFF2B5278; OTHER = 0xFF1F2A36; SYSTEM_BODY = 0xFF2E2224;
            SEP = 0xFF1E2833; BAR = 0xFF0A1018; TIME = 0xFF7F93A8; QUOTE = 0xFF9FB3C8; HINT = 0xFF5E6B78;
            TOAST_BG = 0xEE10161D; TILE_GLOBE = 0xFF24557F; TILE_GROUP = 0xFF3C4A5C; TILE_INVITE = 0xFF5A4A1E; DATE_BG = 0xFF1A2430;
            int acc = system ? 0xFF4FA3FF : accentColor(accent);
            ACCENT = acc;
            OWN = mix(acc, SCREEN, 0.62f);
            TILE_GLOBE = mix(acc, SCREEN, 0.5f);
            ACCENT_DARK = mix(acc, BODY, 0.45f);
            return;
        }
        int[] c = screenColors(system ? "amber" : screen);
        int bg = c[0], fg = c[1], dim = c[2], mid = c[3], panel = c[4];
        crt = system || !screen.equals("lcd");
        SCREEN = bg; PANEL = panel; ROW_HOVER = (fg & 0xFFFFFF) | 0x18000000;
        TEXT = fg; DIM = dim; ACCENT = mid; RED = fg; GREEN = mid; GOLD = fg;
        OWN = panel; OTHER = panel; SEP = dim; BAR = fg; TIME = dim; QUOTE = dim; HINT = dim;
        TOAST_BG = fg; TILE_GLOBE = 0; TILE_GROUP = 0; TILE_INVITE = 0; DATE_BG = 0;
        BODY = system ? 0xFF963028 : 0xFFC0B69E; BODY_LIGHT = system ? 0xFFB24A3E : 0xFFDCD4BE;
        BODY_DARK = system ? 0xFF601C18 : 0xFF847A66; SYSTEM_BODY = BODY;
    }

    /** Ретро пишет заглавными. Метки сервера («#deleted», «#note:») — в слова. */
    public static String up(String s) {
        s = human(s);
        return retro ? s.toUpperCase(java.util.Locale.ROOT) : s;
    }

    /** Сервер шлёт метки без языка: удалённая учётка, заметка в превью чата. */
    public static String human(String s) {
        if (s.contains("#deleted")) s = s.replace("#deleted", Component.translatable("rppda.account.deleted").getString());
        if (s.contains("#note:")) s = s.replace("#note:", Component.translatable("rppda.note.preview").getString());
        if (s.contains("#file:")) s = s.replace("#file:", Component.translatable("rppda.xfer.preview").getString());
        return s;
    }

    /** Номер значка в атласе — тот же порядок, что в {@code scripts/gen_icons.py}. */
    public enum Icon {
        CHATS, CONTACTS, PROFILE, LOCK, UNLOCK, CLOSE, BACK, SEND,
        PLUS, GROUP, CHANNEL, BELL, PDA_ONLY, MUTE, MEMBERS, LEAVE,
        KICK, CROWN, REPLY, TRASH, COPY, KEY, SILENT, SOUND,
        ACCOUNTS, LOG, SWITCH, GIVE, TELEPORT, EDIT, BLOCK, CHECK,
        SEARCH, MESSAGE, PDA, REFRESH, PERSON_ADD, SYSTEM, GLOBE, EYE,
        BRUSH, SIGNAL, NO_SIGNAL, MASK, NOTE, SAVE
    }

    public static void icon(GuiGraphics g, Icon icon, int x, int y, int color) {
        // Оба вида — попиксельные ретро-значки: чёткие при любом масштабе.
        iconFrom(g, ICONS_RETRO, icon, x, y, color);
    }

    public static void iconFrom(GuiGraphics g, ResourceLocation atlas, Icon icon, int x, int y, int color) {
        int n = icon.ordinal();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f,
                ((color >>> 24) & 255) / 255f);
        g.blit(atlas, x, y, (n % 8) * 16, (n / 8) * 16, 16, 16, ATLAS_W, ATLAS_H);
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    /**
     * Значок заданного размера. Значки попиксельные — дробно не масштабируются (иначе «плывут»): 1:1 с тем же центром,
     * крупные (от 24) — ровно ×2.
     */
    public static void icon(GuiGraphics g, Icon icon, int x, int y, int size, int color) {
        int scale = size >= 24 ? 2 : 1;
        int cx = x + size / 2, cy = y + size / 2;
        g.pose().pushPose();
        g.pose().translate(cx - 8 * scale, cy - 8 * scale, 0);
        g.pose().scale(scale, scale, 1);
        icon(g, icon, 0, 0, color);
        g.pose().popPose();
    }

    /** Поле ввода Minecraft со звуком КПК при щелчке (а не ванильным). */
    public static class Box extends net.minecraft.client.gui.components.EditBox {
        public Box(Font font, int x, int y, int w, int h, Component name) {
            super(font, x, y, w, h, name);
        }

        @Override
        public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {
            Sfx.play(Sfx.FOCUS, 0.4f);
        }
    }

    /** Кнопка-значок. Название — подсказкой при наведении дольше секунды. */
    public static class IconButton extends Button {
        public Icon icon;
        public int color = TEXT;
        /** Звук нажатия: свой или по значку (назад/закрыть — отмена, остальное — нажатие). */
        @Nullable
        public net.minecraft.sounds.SoundEvent click;
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

        /** Ретро: значок люминофором; наведение — рамка, кнопка с подписью — инверсия. */
        private void renderRetro(GuiGraphics g) {
            Font font = Minecraft.getInstance().font;
            int x = getX(), y = getY();
            boolean hot = isHoveredOrFocused() && active;
            if (label != null) {
                g.fill(x, y, x + width, y + height, hot ? TEXT : ACCENT);
                String l = up(label.getString());
                int tw = 16 + 2 + font.width(l);
                int x0 = x + (width - tw) / 2;
                icon(g, icon, x0, y + (height - 16) / 2, SCREEN);
                g.drawString(font, l, x0 + 18, y + (height - 8) / 2, SCREEN, false);
                return;
            }
            if (hot) g.renderOutline(x, y, width, height, TEXT);
            int c = !active ? DIM : color;
            icon(g, icon, x + (width - 16) / 2, y + (height - 16) / 2, c);
        }

        @Override
        public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {
            Sfx.play(click != null ? click : icon == Icon.BACK || icon == Icon.CLOSE ? Sfx.CANCEL : Sfx.PRESS, 0.5f);
        }

        public void retip(Component tooltip) {
            setMessage(tooltip);
            setTooltip(Tooltip.create(tooltip));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            if (retro) {
                renderRetro(g);
                return;
            }
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

    /**
     * Аватар: вид, выбранный в профиле ({@link Avatars}): лицо персонажа, если игрок в сети, или знаки (буква имени)
     * на цвете (выбранном или по ID). Система — звезда.
     */
    public static void avatar(GuiGraphics g, int x, int y, int size, @Nullable UUID owner, String name, int id) {
        avatar(g, x, y, size, owner, name, id, Avatars.get(id));
    }

    /** То же с заданным видом (предпросмотр на странице «Аватарка»). */
    public static void avatar(GuiGraphics g, int x, int y, int size, @Nullable UUID owner, String name, int id, Avatars.Style st) {
        String mark = st.mark().isEmpty() ? (name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1))) : st.mark();
        Font font = Minecraft.getInstance().font;
        if (retro) {
            // Люминофор: рамка; лицо, портрет и картинка — перекрашены в два цвета экрана; иначе знаки (у Системы — звезда).
            g.renderOutline(x, y, size, size, DIM);
            if (id == 0 && owner == null) {
                icon(g, Icon.SYSTEM, x + 1, y + 1, size - 2, TEXT);
                return;
            }
            if (Duotone.draw(g, retroSource(st, owner, id), st.mode() != 3, x + 1, y + 1, size - 2, SCREEN, TEXT)) return;
            mark(g, font, up(mark), x, y, size, size >= 24 ? 2f : 1f, TEXT);
            return;
        }
        if (id == 0 && owner == null) {
            g.fill(x, y, x + size, y + size, 0xFF8E2A2A);
            icon(g, Icon.SYSTEM, x + 1, y + 1, size - 2, 0xFFFFFFFF);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean portrait = st.mode() == 0 || st.mode() == 2;
        if (portrait && owner != null && faygolover.rpcore.client.photo.ClientPhotos.drawFace(g, owner, x, y, size)) return;
        if (st.mode() == 3 && AvatarPics.draw(g, id, st.pic(), x, y, size)) return;
        if (portrait && owner != null && mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(owner);
            if (info != null) {
                PlayerFaceRenderer.draw(g, info.getSkinLocation(), x, y, size);
                return;
            }
        }
        Integer chosen = Avatars.color(st);
        int hue = Math.floorMod(id * 2654435761L >>> 8, 360);
        g.fill(x, y, x + size, y + size, chosen != null ? chosen : 0xFF000000 | hsv(hue, 0.45f, 0.62f));
        mark(g, font, st.mark().isEmpty() ? mark.toUpperCase() : mark, x, y, size, size / 16f, 0xFFFFFFFF);
    }

    /** Что перекрашивать в ретро: скин (лицо, пока игрок в сети), фото скина (портрет) или свою картинку; null — знаки. */
    @Nullable
    private static ResourceLocation retroSource(Avatars.Style st, @Nullable UUID owner, int id) {
        Minecraft mc = Minecraft.getInstance();
        return switch (st.mode()) {
            case 0, 2 -> {
                if (owner == null) yield null;
                ResourceLocation photo = faygolover.rpcore.client.photo.ClientPhotos.skin(owner);
                if (photo != null) yield photo;
                PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(owner);
                yield info == null ? null : info.getSkinLocation();
            }
            case 3 -> st.pic() > 0 ? faygolover.rpcore.client.photo.ClientPictures.texture("rppda:avatar/" + id) : null;
            default -> null;
        };
    }

    /** Знаки по центру квадрата; два широких — мельче, чтобы влезли. */
    private static void mark(GuiGraphics g, Font font, String text, int x, int y, int size, float scale, int color) {
        int w = Math.max(1, font.width(text));
        scale = Math.min(scale, (size - 2) / (float) w);
        g.pose().pushPose();
        g.pose().translate(x + size / 2f, y + size / 2f, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, -w / 2, -4, color, false);
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

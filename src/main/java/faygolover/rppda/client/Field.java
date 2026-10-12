package faygolover.rppda.client;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Однострочное поле в стиле КПК (вместо поля Minecraft): без чёрной подложки и тени, текст по высоте по центру,
 * курсор считается по тому, что на экране (у пароля — по звёздочкам). Ретро — рамка люминофором, современный —
 * панель с рамкой акцентом в фокусе.
 */
public class Field extends AbstractWidget {
    private String value = "";
    private int cursor;
    private int maxLength = 32;
    private boolean masked;
    private boolean centered;
    private String hint = "";
    private int scrollFrom;
    private long focusTime;
    private final Font font = Minecraft.getInstance().font;

    public Field(int x, int y, int w, int h, Component name) {
        super(x, y, w, h, name);
    }

    public Field masked(boolean m) {
        masked = m;
        return this;
    }

    public Field centered(boolean c) {
        centered = c;
        return this;
    }

    public Field hint(String h) {
        hint = h;
        return this;
    }

    public Field max(int n) {
        maxLength = n;
        return this;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String v) {
        value = v.length() > maxLength ? v.substring(0, maxLength) : v;
        cursor = value.length();
    }

    /** Что видно: звёздочки у пароля; в ретро — заглавными (кроме пароля: регистр важен). */
    private String shown(String s) {
        if (masked) return "*".repeat(s.length());
        return Ui.retro ? s.toUpperCase(java.util.Locale.ROOT) : s;
    }

    @Override
    public void setFocused(boolean f) {
        super.setFocused(f);
        if (f) focusTime = System.currentTimeMillis();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        int x = getX(), y = getY(), w = width, h = height;
        boolean focus = isFocused();
        if (Ui.retro) {
            g.renderOutline(x, y, w, h, focus ? Ui.TEXT : Ui.DIM);
        } else {
            g.fill(x, y, x + w, y + h, Ui.PANEL);
            g.renderOutline(x, y, w, h, focus ? Ui.ACCENT : Ui.SEP);
        }
        int inner = w - 8;
        int ty = y + (h - 8) / 2 + (Ui.retro ? 0 : 1);
        if (value.isEmpty()) {
            String hs = Ui.up(hint);
            int hx = centered ? x + (w - font.width(hs)) / 2 : x + 4;
            if (!focus || centered) g.drawString(font, Ui.fit(font, hs, inner), hx, ty, Ui.HINT, false);
            if (focus) drawCursor(g, centered ? x + w / 2 : x + 4, ty);
            return;
        }
        // Окно текста — так, чтобы курсор был виден.
        scrollFrom = Math.min(scrollFrom, cursor);
        while (scrollFrom < cursor && font.width(shown(value.substring(scrollFrom, cursor))) > inner) scrollFrom++;
        String vis = value.substring(scrollFrom);
        while (!vis.isEmpty() && font.width(shown(vis)) > inner) vis = vis.substring(0, vis.length() - 1);
        String s = shown(vis);
        int tx = centered && scrollFrom == 0 ? x + (w - font.width(s)) / 2 : x + 4;
        g.drawString(font, s, tx, ty, Ui.TEXT, false);
        if (focus) drawCursor(g, tx + font.width(shown(value.substring(scrollFrom, Math.min(cursor, scrollFrom + vis.length())))), ty);
    }

    private void drawCursor(GuiGraphics g, int cx, int ty) {
        if ((System.currentTimeMillis() - focusTime) / 500 % 2 != 0) return;
        g.fill(cx, ty - 1, cx + 1, ty + 9, Ui.TEXT);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (!isFocused() || !SharedConstants.isAllowedChatCharacter(c) || value.length() >= maxLength) return false;
        value = value.substring(0, cursor) + c + value.substring(cursor);
        cursor++;
        focusTime = System.currentTimeMillis();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!isFocused()) return false;
        focusTime = System.currentTimeMillis();
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursor > 0) {
                    int from = Screen.hasControlDown() ? 0 : cursor - 1;
                    value = value.substring(0, from) + value.substring(cursor);
                    cursor = from;
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < value.length()) value = value.substring(0, cursor) + value.substring(cursor + 1);
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                cursor = Math.max(0, cursor - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                cursor = Math.min(value.length(), cursor + 1);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                cursor = 0;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                cursor = value.length();
                return true;
            }
            default -> {}
        }
        if (Screen.isPaste(key)) {
            String clip = SharedConstants.filterText(Minecraft.getInstance().keyboardHandler.getClipboard());
            String add = clip.substring(0, Math.min(clip.length(), maxLength - value.length()));
            value = value.substring(0, cursor) + add + value.substring(cursor);
            cursor += add.length();
            return true;
        }
        if (Screen.isSelectAll(key)) {
            cursor = value.length();
            return true;
        }
        return false;
    }

    @Override
    public void onClick(double mx, double my) {
        // Курсор — к ближайшему символу (по тому, что на экране).
        int x = (int) mx - getX() - 4;
        int best = scrollFrom, bestD = Integer.MAX_VALUE;
        for (int i = scrollFrom; i <= value.length(); i++) {
            int d = Math.abs(font.width(shown(value.substring(scrollFrom, i))) - x);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        cursor = centered && scrollFrom == 0 ? value.length() : best;
        focusTime = System.currentTimeMillis();
    }

    /** Щелчок по полю — звук КПК, а не ванильный. */
    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {
        Sfx.play(Sfx.FOCUS, 0.4f);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}

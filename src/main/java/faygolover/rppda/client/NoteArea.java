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

import java.util.ArrayList;
import java.util.List;

/**
 * Текст заметки в стиле КПК: фон экрана, без тени и чёрной рамки Minecraft. Чтение — только прокрутка; правка —
 * курсор, стрелки, Enter, вставка. Строки переносятся по словам по ширине того, что на экране (в ретро — заглавные).
 */
public class NoteArea extends AbstractWidget {
    /** Рисует строку текста (окно КПК: в ретро — со свечением люминофора). */
    public interface Drawer {
        void draw(GuiGraphics g, String s, int x, int y, int color);
    }

    public static final int LINE = 10;
    private final Font font = Minecraft.getInstance().font;
    private final Drawer drawer;
    private String value = "";
    private int cursor;
    private int maxLength = 2000;
    private boolean editable;
    private double scroll;
    private long blink;
    private String hint = "";
    private int textColor = 0;

    public NoteArea(int x, int y, int w, int h, Drawer drawer) {
        super(x, y, w, h, Component.empty());
        this.drawer = drawer;
    }

    public NoteArea editable(boolean e) {
        editable = e;
        return this;
    }

    public NoteArea hint(String h) {
        hint = h;
        return this;
    }

    public NoteArea max(int n) {
        maxLength = n;
        return this;
    }

    public NoteArea color(int c) {
        textColor = c;
        return this;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String v) {
        value = v.length() > maxLength ? v.substring(0, maxLength) : v;
        cursor = editable ? value.length() : 0;
    }

    static char shownChar(char c) {
        return Ui.retro ? Character.toUpperCase(c) : c;
    }

    static String shown(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) b.append(shownChar(s.charAt(i)));
        return b.toString();
    }

    /** Строки после переноса: [начало, конец) в исходном тексте (перевод строки в строку не входит). */
    public static List<int[]> wrap(Font font, String text, int width) {
        List<int[]> out = new ArrayList<>();
        int start = 0;
        while (true) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            // Одна «абзацная» строка — по ширине.
            int s = start;
            while (true) {
                int e = s, lastSpace = -1, w = 0;
                while (e < end) {
                    w += font.width(String.valueOf(shownChar(text.charAt(e))));
                    if (w > width) break;
                    if (text.charAt(e) == ' ') lastSpace = e;
                    e++;
                }
                if (e >= end) {
                    out.add(new int[]{s, end});
                    break;
                }
                int cut = lastSpace > s ? lastSpace + 1 : Math.max(e, s + 1);
                out.add(new int[]{s, cut});
                s = cut;
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        return out;
    }

    private int innerW() {
        return width - 8;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        int x = getX(), y = getY();
        if (editable) {
            if (Ui.retro) g.renderOutline(x, y, width, height, isFocused() ? Ui.DIM : Ui.PANEL);
            else g.renderOutline(x, y, width, height, isFocused() ? Ui.ACCENT : Ui.SEP);
        }
        List<int[]> lines = wrap(font, value, innerW());
        int total = lines.size() * LINE;
        int view = height - 6;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - view)));
        int color = textColor != 0 ? textColor : Ui.TEXT;
        g.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        if (value.isEmpty() && !hint.isEmpty()) drawer.draw(g, shown(hint), x + 4, y + 4, Ui.HINT);
        int ly = y + 4 - (int) scroll;
        for (int[] l : lines) {
            if (ly + LINE > y && ly < y + height) drawer.draw(g, shown(value.substring(l[0], l[1])), x + 4, ly, color);
            ly += LINE;
        }
        if (editable && isFocused() && (System.currentTimeMillis() - blink) / 500 % 2 == 0) {
            int[] pos = cursorPos(lines);
            int cx = x + 4 + pos[0], cy = y + 4 + pos[1] * LINE - (int) scroll;
            g.fill(cx, cy - 1, cx + 1, cy + 9, Ui.TEXT);
        }
        g.disableScissor();
        if (total > view) {
            // Полоса прокрутки.
            int barH = Math.max(8, view * view / total);
            int barY = y + 3 + (int) ((view - barH) * (scroll / Math.max(1, total - view)));
            g.fill(x + width - 3, barY, x + width - 2, barY + barH, Ui.DIM);
        }
    }

    /** Строка и смещение курсора: {x в пикселях, номер строки}. */
    private int[] cursorPos(List<int[]> lines) {
        for (int i = 0; i < lines.size(); i++) {
            int[] l = lines.get(i);
            boolean last = i == lines.size() - 1;
            boolean nextStartsHere = !last && lines.get(i + 1)[0] == cursor && lines.get(i + 1)[0] == l[1];
            if (cursor >= l[0] && cursor <= l[1] && !nextStartsHere) {
                return new int[]{font.width(shown(value.substring(l[0], cursor))), i};
            }
        }
        return new int[]{0, Math.max(0, lines.size() - 1)};
    }

    private void keepCursorVisible() {
        List<int[]> lines = wrap(font, value, innerW());
        int line = cursorPos(lines)[1];
        int top = line * LINE, view = height - 6;
        if (top < scroll) scroll = top;
        if (top + LINE > scroll + view) scroll = top + LINE - view;
        blink = System.currentTimeMillis();
    }

    private void insert(String s) {
        if (!editable) return;
        s = s.replace("\r", "");
        int room = maxLength - value.length();
        if (room <= 0) return;
        if (s.length() > room) s = s.substring(0, room);
        value = value.substring(0, cursor) + s + value.substring(cursor);
        cursor += s.length();
        keepCursorVisible();
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (!editable || !isFocused() || !SharedConstants.isAllowedChatCharacter(c)) return false;
        insert(String.valueOf(c));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!isFocused()) return false;
        if (!editable) {
            if (key == GLFW.GLFW_KEY_UP) scroll -= LINE;
            else if (key == GLFW.GLFW_KEY_DOWN) scroll += LINE;
            else return false;
            return true;
        }
        List<int[]> lines = wrap(font, value, innerW());
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> insert("\n");
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursor > 0) {
                    value = value.substring(0, cursor - 1) + value.substring(cursor);
                    cursor--;
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < value.length()) value = value.substring(0, cursor) + value.substring(cursor + 1);
            }
            case GLFW.GLFW_KEY_LEFT -> cursor = Math.max(0, cursor - 1);
            case GLFW.GLFW_KEY_RIGHT -> cursor = Math.min(value.length(), cursor + 1);
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                int[] pos = cursorPos(lines);
                int target = pos[1] + (key == GLFW.GLFW_KEY_UP ? -1 : 1);
                if (target >= 0 && target < lines.size()) cursor = indexAt(lines.get(target), pos[0]);
            }
            case GLFW.GLFW_KEY_HOME -> cursor = lines.get(cursorPos(lines)[1])[0];
            case GLFW.GLFW_KEY_END -> cursor = lines.get(cursorPos(lines)[1])[1];
            default -> {
                if (Screen.isPaste(key)) {
                    insert(Minecraft.getInstance().keyboardHandler.getClipboard());
                    return true;
                }
                if (Screen.isCopy(key)) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(value);
                    return true;
                }
                return false;
            }
        }
        keepCursorVisible();
        return true;
    }

    private int indexAt(int[] line, int px) {
        int best = line[0], bestD = Integer.MAX_VALUE;
        for (int i = line[0]; i <= line[1]; i++) {
            int d = Math.abs(font.width(shown(value.substring(line[0], i))) - px);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    public void onClick(double mx, double my) {
        if (!editable) return;
        List<int[]> lines = wrap(font, value, innerW());
        if (lines.isEmpty()) return;
        int line = (int) ((my - getY() - 4 + scroll) / LINE);
        line = Math.max(0, Math.min(lines.size() - 1, line));
        cursor = indexAt(lines.get(line), (int) mx - getX() - 4);
        blink = System.currentTimeMillis();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!isMouseOver(mx, my)) return false;
        scroll -= delta * LINE * 2;
        return true;
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

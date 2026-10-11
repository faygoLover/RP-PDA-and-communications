package faygolover.rppda.api;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Разделы КПК от аддонов (только клиент). Раздел получает свою клавишу корпуса (ретро) или кнопку слева
 * (современный): клавиши сами ужимаются, а когда не помещаются — листаются. Данные раздел гоняет по своей сети.
 * Регистрировать — в {@code FMLClientSetupEvent}.
 */
public final class PdaApps {
    private PdaApps() {}

    public interface App {
        String id();

        Component title();

        /** Значок 16×16 в (x, y) цветом {@code color} (ARGB, уже с учётом вида и наведения). */
        void icon(GuiGraphics g, int x, int y, int color);

        /** Показывать в КПК игрока. */
        default boolean player() {
            return true;
        }

        /** Показывать в системном КПК. */
        default boolean system() {
            return false;
        }

        /** Раздел открыт (и каждый раз, когда окно пересобирается: тогда же добавлять свои поля ввода). */
        void open(Host host);

        void render(GuiGraphics g, int mouseX, int mouseY, float partialTick);

        default boolean mouseClicked(double x, double y, int button) {
            return false;
        }

        default boolean mouseScrolled(double x, double y, double delta) {
            return false;
        }

        default boolean keyPressed(int key, int scan, int mods) {
            return false;
        }

        default void close() {}
    }

    /** Что окно КПК даёт разделу: место, цвета текущего вида, шрифт, добавление полей ввода. */
    public interface Host {
        int x();

        int y();

        int width();

        int height();

        /** Ретро-вид (люминофор, заглавные). */
        boolean retro();

        int textColor();

        int dimColor();

        int accentColor();

        int screenColor();

        Font font();

        /** Текст в стиле вида (в ретро — заглавными и со свечением). */
        void text(GuiGraphics g, String s, int x, int y, int color);

        <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget);

        /** ID учётки, от которой работает окно (0 — Система). */
        int account();
    }

    private static final List<App> APPS = new CopyOnWriteArrayList<>();

    public static void register(App app) {
        for (App a : APPS) if (a.id().equals(app.id())) return;
        APPS.add(app);
    }

    public static List<App> all() {
        return List.copyOf(APPS);
    }
}

package faygolover.rppda.api;

import faygolover.rppda.data.Look;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Корпуса КПК от аддонов. {@link #registerBody} — на сервере и клиенте (в конструкторе мода): без него сервер не
 * примет этот корпус. {@link #registerVisual} — только клиент: текстура корпуса 360×254 (как у
 * {@code textures/gui/skin/*.png}) или цвет одноцветного. Подписи — ключи перевода {@code rppda.skin.<id>} (название в
 * списке) и {@code rppda.model.<id>} (стандартное название модели на корпусе).
 */
public final class PdaSkins {
    private PdaSkins() {}

    public static void registerBody(String id) {
        Look.registerBody(id);
    }

    /** {@code texture} — null для одноцветного ({@code color} ARGB); {@code dark} — тёмный корпус (клавиши светлее). */
    public static void registerVisual(String id, @Nullable ResourceLocation texture, int color, int labelColor, boolean dark) {
        Look.registerBody(id);
        faygolover.rppda.client.Skins.register(id, texture, color, labelColor, dark);
    }
}

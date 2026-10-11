package faygolover.rppda.client;

import faygolover.rppda.RpPda;
import faygolover.rppda.data.Look;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Корпуса ретро-КПК: цвет (одноцветные) или текстура {@code textures/gui/skin/<id>.png} (с рисунком, из
 * {@code scripts/gen_skins.py}), цвет и место надписи модели, рамка экрана, светлые/тёмные клавиши.
 * Образцы для выбора — {@code textures/gui/skin_swatches.png} (28×20, по 8 в ряд, порядок как {@link Look#BODIES}).
 */
public final class Skins {
    private Skins() {}

    public static final ResourceLocation SWATCHES = new ResourceLocation(RpPda.MODID, "textures/gui/skin_swatches.png");

    /** {@code color} — цвет одноцветного (0 — с рисунком); {@code labelX} — надпись модели (из 360 по ширине). */
    public record Skin(String id, int color, int label, int labelX, int bezel, boolean dark) {
        public boolean textured() {
            return color == 0;
        }

        public ResourceLocation texture() {
            return new ResourceLocation(RpPda.MODID, "textures/gui/skin/" + id + ".png");
        }
    }

    private static final int BEZEL = 0xFF2A2724;
    private static final Map<String, Skin> ALL = new LinkedHashMap<>();

    static {
        add("beige", 0xFFC0B69E, 0xFF50483C, false);
        add("graphite", 0xFF424448, 0xFFAAAAA6, true);
        add("olive", 0xFF5C623E, 0xFF282C1A, false);
        add("navy", 0xFF283652, 0xFF96AAC8, true);
        add("white", 0xFFDEDED6, 0xFF5A5A60, false);
        add("khaki", 0xFFA28E62, 0xFF483C24, false);
        add("orange", 0xFFD6702A, 0xFF3C1E0A, false);
        add("camo", 0, 0xFFE6E2C8, false);
        ALL.put("wood", new Skin("wood", 0, 0xFFECDCB4, 12, 0xFF969696, false));
        add("duo", 0, 0xFF50483C, false);
        add("worn", 0, 0xFF282A1C, false);
        add("field", 0, 0xFFE2BE28, true);
        add("rainbow", 0, 0xFF605442, false);
        add("sunset", 0, 0xFFFFE2F0, true);
        add("neon", 0, 0xFF00E6FF, true);
        add("memphis", 0, 0xFF28282C, false);
        add("cassette", 0, 0xFFE6E6E6, true);
        ALL.put("wave", new Skin("wave", 0, 0xFF46382E, 214, BEZEL, false));
        add("disco", 0, 0xFFF0B43C, true);
    }

    private static void add(String id, int color, int label, boolean dark) {
        ALL.put(id, new Skin(id, color, label, 12, BEZEL, dark));
    }

    public static Skin get(String id) {
        Skin s = ALL.get(id);
        return s == null ? ALL.get("beige") : s;
    }

    public static int index(String id) {
        return Math.max(0, Look.BODIES.indexOf(id));
    }
}

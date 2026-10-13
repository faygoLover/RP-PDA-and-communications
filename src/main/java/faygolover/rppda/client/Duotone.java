package faygolover.rppda.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import faygolover.rppda.RpPda;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Аватарки в ретро-КПК ({@code docs/20_retro_memory.md} §3): картинка перекрашивается в плавный переход между двумя
 * цветами экрана — каждая точка по своей яркости, тёмное к тёмному цвету, светлое к светлому (на ЖК тёмный — текст).
 * Перекрашенное держится готовой текстурой (по картинке и паре цветов), пока окно не забудет её.
 */
public final class Duotone {
    private Duotone() {}

    private static final int LIMIT = 64;
    /** Картинка ещё не пришла (скин качается) — переспросить не чаще. */
    private static final long RETRY_MS = 1000;

    private record Done(ResourceLocation tex, int w, int h) {}

    private static final Map<String, Done> cache = new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<String, Long> failed = new HashMap<>();

    /**
     * Нарисовать перекрашенное лицо со скина ({@code face} — лицо со слоем шапки, как {@code PlayerFaceRenderer}) или
     * картинку целиком. false — нечего рисовать (вызывающий рисует знаки).
     */
    public static boolean draw(GuiGraphics g, @Nullable ResourceLocation src, boolean face, int x, int y, int size, int c1, int c2) {
        if (src == null) return false;
        String key = src + (face ? "#face" : "#pic") + "|" + c1 + "|" + c2;
        Done d = cache.get(key);
        if (d == null) {
            Long f = failed.get(key);
            if (f != null && System.currentTimeMillis() - f < RETRY_MS) return false;
            d = make(src, face, c1, c2);
            if (d == null) {
                failed.put(key, System.currentTimeMillis());
                return false;
            }
            failed.remove(key);
            cache.put(key, d);
            trim();
        }
        RenderSystem.enableBlend();
        g.blit(d.tex, x, y, size, size, 0, 0, d.w, d.h, d.w, d.h);
        return true;
    }

    static void clear() {
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        for (Done d : cache.values()) tm.release(d.tex);
        cache.clear();
        failed.clear();
    }

    private static void trim() {
        if (cache.size() <= LIMIT) return;
        var it = cache.entrySet().iterator();
        Done d = it.next().getValue();
        it.remove();
        Minecraft.getInstance().getTextureManager().release(d.tex);
    }

    @Nullable
    private static Done make(ResourceLocation src, boolean face, int c1, int c2) {
        NativeImage img = pixels(src);
        if (img == null) return null;
        try {
            NativeImage part = face ? face(img) : copy(img);
            if (part == null) return null;
            if (!paint(part, c1, c2)) {
                part.close();
                return null;
            }
            int w = part.getWidth(), h = part.getHeight();
            ResourceLocation tex = Minecraft.getInstance().getTextureManager().register("rppda_duotone", new DynamicTexture(part));
            return new Done(tex, w, h);
        } finally {
            img.close();
        }
    }

    /** Точки текстуры: у динамической — из памяти, у остальных (скины) — с видеокарты. */
    @Nullable
    private static NativeImage pixels(ResourceLocation src) {
        try {
            AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(src);
            if (t instanceof DynamicTexture d && d.getPixels() != null) return copy(d.getPixels());
            RenderSystem.bindTexture(t.getId());
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (w <= 0 || h <= 0 || w > 1024 || h > 1024) return null;
            NativeImage n = new NativeImage(w, h, false);
            n.downloadTexture(0, false);
            return n;
        } catch (Exception ex) {
            RpPda.LOGGER.debug("RP PDA: картинка {} не читается для ретро-аватарки: {}", src, ex.toString());
            return null;
        }
    }

    private static NativeImage copy(NativeImage src) {
        NativeImage n = new NativeImage(src.getWidth(), src.getHeight(), false);
        n.copyFrom(src);
        return n;
    }

    /** Лицо скина с верхним слоем: квадрат 1/8 ширины в (1/8, 1/8), шапка — в (5/8, 1/8). */
    @Nullable
    private static NativeImage face(NativeImage skin) {
        int u = skin.getWidth() / 8;
        if (u <= 0 || skin.getHeight() < 2 * u) return null;
        // Скин ещё не пришёл (пустая текстура) — подождать.
        boolean any = false;
        for (int y = 0; y < u && !any; y++) for (int x = 0; x < u; x++) if ((skin.getPixelRGBA(u + x, u + y) >>> 24) > 0) any = true;
        if (!any) return null;
        NativeImage out = new NativeImage(u, u, false);
        for (int y = 0; y < u; y++) {
            for (int x = 0; x < u; x++) {
                int base = skin.getPixelRGBA(u + x, u + y) | 0xFF000000;
                int hat = skin.getPixelRGBA(5 * u + x, u + y);
                out.setPixelRGBA(x, y, over(hat, base));
            }
        }
        return out;
    }

    /** Наложить {@code top} (с прозрачностью) на непрозрачный {@code base}; цвета — как в NativeImage (ABGR). */
    private static int over(int top, int base) {
        int a = top >>> 24;
        if (a == 0) return base;
        if (a == 255) return top;
        int r = mixc(top & 255, base & 255, a), g = mixc((top >> 8) & 255, (base >> 8) & 255, a),
                b = mixc((top >> 16) & 255, (base >> 16) & 255, a);
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    private static int mixc(int top, int base, int a) {
        return (top * a + base * (255 - a)) / 255;
    }

    /**
     * Перекрасить на месте: яркость точки (растянута — самая тёмная к тёмному, самая светлая к светлому, полутона
     * чуть светлее) → цвет между двумя. Прозрачное остаётся прозрачным. false — картинка пустая.
     */
    private static boolean paint(NativeImage img, int c1, int c2) {
        int w = img.getWidth(), h = img.getHeight();
        float[] lum = new float[w * h];
        int n = 0;
        float[] seen = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = img.getPixelRGBA(x, y);
                float l = 0.299f * (c & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * ((c >> 16) & 255);
                lum[y * w + x] = l;
                if ((c >>> 24) > 0) seen[n++] = l;
            }
        }
        if (n == 0) return false;
        float[] sorted = Arrays.copyOf(seen, n);
        Arrays.sort(sorted);
        float lo = sorted[Math.min(2, n - 1)], hi = sorted[n - 1];
        boolean firstDark = luma(c1) <= luma(c2);
        int dark = firstDark ? c1 : c2, light = firstDark ? c2 : c1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = img.getPixelRGBA(x, y);
                int a = c >>> 24;
                if (a == 0) continue;
                float t = hi > lo ? Math.max(0, Math.min(1, (lum[y * w + x] - lo) / (hi - lo))) : 0.5f;
                t = 0.08f + 0.92f * (float) Math.pow(t, 0.6);
                int r = lerp((dark >> 16) & 255, (light >> 16) & 255, t), g = lerp((dark >> 8) & 255, (light >> 8) & 255, t),
                        b = lerp(dark & 255, light & 255, t);
                img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return true;
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    /** Яркость цвета ARGB. */
    private static float luma(int argb) {
        return 0.299f * ((argb >> 16) & 255) + 0.587f * ((argb >> 8) & 255) + 0.114f * (argb & 255);
    }
}

package faygolover.rppda.client;

import com.mojang.blaze3d.platform.NativeImage;
import faygolover.rppda.RpPda;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Свои картинки-аватарки (§7.6): по ID учётки и номеру картинки — у сервера, кэш текстур до смены номера или выхода.
 * Выбор файла с компьютера: окно выбора, квадрат по центру, 64×64 PNG.
 */
public final class AvatarPics {
    private AvatarPics() {}

    public static final int SIZE = 64;
    public static final int MAX_BYTES = 20000;
    /** Больше — не читаем (картинка всё равно уменьшится до 64×64). */
    private static final long MAX_FILE = 20L * 1024 * 1024;
    private static final long RETRY_MS = 10_000L;

    private static final class Entry {
        int version;
        long askedAt;
        @Nullable ResourceLocation texture;
    }

    private static final Map<Integer, Entry> cache = new HashMap<>();

    /** Нарисовать картинку учётки; false — нет или ещё не пришла (рисуется запасной вид). */
    public static boolean draw(GuiGraphics g, int id, int version, int x, int y, int size) {
        if (version <= 0 || Minecraft.getInstance().getConnection() == null) return false;
        Entry e = cache.computeIfAbsent(id, k -> new Entry());
        long now = System.currentTimeMillis();
        if (e.version != version && (e.askedAt == 0 || now - e.askedAt > RETRY_MS)) {
            e.askedAt = now;
            CompoundTag t = new CompoundTag();
            t.putInt("id", id);
            ClientPda.send("avatar.pic", t);
        }
        if (e.texture == null) return false;
        g.blit(e.texture, x, y, size, size, 0, 0, SIZE, SIZE, SIZE, SIZE);
        return true;
    }

    static void receive(CompoundTag d) {
        Entry e = cache.computeIfAbsent(d.getInt("id"), k -> new Entry());
        release(e);
        e.version = d.getInt("v");
        e.askedAt = 0;
        byte[] png = d.getByteArray("png");
        if (png.length > 0) e.texture = register(png);
    }

    /** Текстура из PNG (предпросмотр и кэш); null — не читается. */
    @Nullable
    static ResourceLocation register(byte[] png) {
        try {
            NativeImage img = NativeImage.read(png);
            return Minecraft.getInstance().getTextureManager().register("rppda_avatar", new DynamicTexture(img));
        } catch (Exception ex) {
            RpPda.LOGGER.warn("RP PDA: картинка-аватарка не читается: {}", ex.toString());
            return null;
        }
    }

    static void release(@Nullable ResourceLocation tex) {
        if (tex != null) Minecraft.getInstance().getTextureManager().release(tex);
    }

    private static void release(Entry e) {
        release(e.texture);
        e.texture = null;
    }

    static void clear() {
        cache.values().forEach(AvatarPics::release);
        cache.clear();
    }

    /**
     * Выбрать файл и сделать из него PNG 64×64. Окно выбора — в отдельном потоке (игра не замирает); ответ — в потоке
     * игры: {@code done} с PNG или {@code fail} с ключом перевода (отмена — ни того, ни другого).
     */
    static void pick(String title, Consumer<byte[]> done, Consumer<String> fail) {
        Thread t = new Thread(() -> {
            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(4);
                filters.put(stack.UTF8("*.png")).put(stack.UTF8("*.jpg")).put(stack.UTF8("*.jpeg")).put(stack.UTF8("*.bmp"));
                filters.flip();
                path = TinyFileDialogs.tinyfd_openFileDialog(title, null, filters, "PNG, JPG, BMP", false);
            } catch (Throwable ex) {
                RpPda.LOGGER.warn("RP PDA: окно выбора файла не открылось: {}", ex.toString());
                Minecraft.getInstance().execute(() -> fail.accept("rppda.err.avatar_pick"));
                return;
            }
            if (path == null) return;
            byte[] png = prepare(new File(path));
            Minecraft.getInstance().execute(() -> {
                if (png == null) fail.accept("rppda.err.avatar_file");
                else done.accept(png);
            });
        }, "RP PDA avatar picker");
        t.setDaemon(true);
        t.start();
    }

    /** Квадрат по центру, уменьшить до 64×64, PNG; null — не картинка или слишком большой файл. */
    @Nullable
    private static byte[] prepare(File f) {
        try {
            if (!f.isFile() || f.length() > MAX_FILE) return null;
            BufferedImage src = ImageIO.read(f);
            if (src == null) return null;
            int side = Math.min(src.getWidth(), src.getHeight());
            BufferedImage sq = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            Image scaled = sq.getScaledInstance(SIZE, SIZE, Image.SCALE_AREA_AVERAGING);
            BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            byte[] png = bytes.toByteArray();
            return png.length <= MAX_BYTES ? png : null;
        } catch (Exception | OutOfMemoryError ex) {
            RpPda.LOGGER.warn("RP PDA: файл {} не подошёл для аватарки: {}", f, ex.toString());
            return null;
        }
    }
}

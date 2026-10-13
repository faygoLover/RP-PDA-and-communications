package faygolover.rppda.client;

import com.mojang.blaze3d.platform.NativeImage;
import faygolover.rpcore.client.photo.ClientPictures;
import faygolover.rppda.RpPda;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
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
import java.util.function.Consumer;

/**
 * Свои картинки-аватарки (§7.6). Хранит и раздаёт RP Core (картинки по ключу {@code rppda:avatar/<ID>}); здесь —
 * показ, выбор файла с компьютера (квадрат по центру, 64×64 PNG) и отправка, когда сервер разрешил.
 */
public final class AvatarPics {
    private AvatarPics() {}

    public static final int SIZE = 64;
    /** Пакет к серверу в Minecraft — до 32 КБ; картинка 64×64 в PNG — не больше ~17 КБ. */
    public static final int MAX_BYTES = 20000;
    /** Больше — не читаем (картинка всё равно уменьшится до 64×64). */
    private static final long MAX_FILE = 20L * 1024 * 1024;

    /** Картинка, ждущая разрешения сервера на загрузку. */
    @Nullable
    private static byte[] outgoing;

    /** Нарисовать картинку учётки; false — нет или ещё не пришла (рисуется запасной вид). */
    public static boolean draw(GuiGraphics g, int id, int version, int x, int y, int size) {
        return version > 0 && ClientPictures.draw(g, "rppda:avatar/" + id, x, y, size, size);
    }

    /** «Готово» с новым файлом: запомнить, отправить, когда сервер пришлёт ключ. */
    static void queue(byte[] png) {
        outgoing = png;
    }

    /** Сервер разрешил: отправить в Core. */
    static void upload(String key) {
        if (outgoing != null) ClientPictures.upload(key, outgoing);
        outgoing = null;
    }

    static void clear() {
        outgoing = null;
    }

    /** Текстура из PNG для предпросмотра; null — не читается. */
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

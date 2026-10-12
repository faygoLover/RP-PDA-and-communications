package faygolover.rppda.server;

import faygolover.rppda.RpPda;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.network.Net;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Своя картинка-аватарка учётки (§7.6). Служба фото RP Core хранит только скин игрока, поэтому картинки — здесь, по
 * тем же правилам: PNG до {@link #MAX_BYTES}, размер из заголовка до распаковки (ровно 64×64 — клиент сам обрезает
 * и уменьшает), не чаще раза в {@code avatar_upload_seconds} от игрока. Файл — {@code <мир>/rppda/avatars/<id>.png}.
 */
public final class AvatarPics {
    private AvatarPics() {}

    public static final int SIZE = 64;
    /** Пакет к серверу в Minecraft — до 32 КБ; картинка 64×64 в PNG — не больше ~17 КБ. */
    public static final int MAX_BYTES = 20000;
    private static final int CACHE_SIZE = 64;
    private static final int QUERIES_PER_SECOND = 20;
    private static final byte[] NONE = new byte[0];

    private static final Map<UUID, Long> lastUpload = new HashMap<>();
    private static final Map<UUID, long[]> queries = new HashMap<>();
    private static final Map<Integer, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> e) {
            return size() > CACHE_SIZE;
        }
    };

    static void reset() {
        lastUpload.clear();
        queries.clear();
        cache.clear();
    }

    static void forget(UUID player) {
        lastUpload.remove(player);
        queries.remove(player);
    }

    private static Path file(PdaStore store, int id) {
        return store.root().resolve("avatars").resolve(id + ".png");
    }

    /**
     * Принять картинку от окна учётки {@code me}. false — отклонена (тост игроку уже ушёл).
     */
    static boolean upload(ServerPlayer sp, PdaStore store, Account me, byte[] png) {
        long now = System.currentTimeMillis();
        Long last = lastUpload.get(sp.getUUID());
        if (last != null && now - last < PdaConfig.avatarUploadSeconds * 1000L) {
            Actions.toast(sp, "rppda.err.avatar_too_soon", true, Long.toString((PdaConfig.avatarUploadSeconds * 1000L - (now - last)) / 1000 + 1));
            return false;
        }
        if (png.length == 0 || png.length > MAX_BYTES || !validPng(png)) {
            RpPda.LOGGER.warn("RP PDA: картинка-аватарка от {} отклонена ({} байт)", sp.getGameProfile().getName(), png.length);
            Actions.toast(sp, "rppda.err.avatar_bad", true);
            return false;
        }
        lastUpload.put(sp.getUUID(), now);
        me.avatarPic++;
        cache.put(me.id, png);
        save(store, me.id, png);
        return true;
    }

    /** ГМ: картинку — вон; вид — «Лицо», если стояла картинка. */
    static void remove(PdaStore store, Account a) {
        if (a.avatarPic == 0) return;
        a.avatarPic = 0;
        if (a.avatar == Account.AV_PICTURE) a.avatar = Account.AV_FACE;
        cache.remove(a.id);
        Path f = file(store, a.id);
        Util.ioPool().execute(() -> {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                RpPda.LOGGER.warn("RP PDA: не удалить картинку {}: {}", f, e.toString());
            }
        });
    }

    /** Клиент просит картинку учётки (рисует аватарку). */
    static void query(ServerPlayer sp, PdaStore store, int id) {
        long second = System.currentTimeMillis() / 1000;
        long[] q = queries.computeIfAbsent(sp.getUUID(), k -> new long[2]);
        if (q[0] != second) {
            q[0] = second;
            q[1] = 0;
        }
        if (++q[1] > QUERIES_PER_SECOND) return;
        Account a = store.accountAny(id);
        byte[] png = a == null || a.deleted || a.avatarPic == 0 ? null : png(store, id);
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putInt("v", a == null ? 0 : a.avatarPic);
        t.putByteArray("png", png == null ? NONE : png);
        Net.send(sp, "avatar.pic", t);
    }

    @Nullable
    private static byte[] png(PdaStore store, int id) {
        byte[] png = cache.get(id);
        if (png == null) {
            Path f = file(store, id);
            try {
                png = Files.exists(f) ? Files.readAllBytes(f) : NONE;
            } catch (IOException e) {
                RpPda.LOGGER.warn("RP PDA: не прочитать картинку {}: {}", f, e.toString());
                png = NONE;
            }
            cache.put(id, png);
        }
        return png.length == 0 ? null : png;
    }

    /** Размер — из заголовка, без распаковки картинки. */
    static boolean validPng(byte[] png) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            if (in == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) return false;
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true);
                return r.getWidth(0) == SIZE && r.getHeight(0) == SIZE;
            } finally {
                r.dispose();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static void save(PdaStore store, int id, byte[] png) {
        Path d = file(store, id).getParent();
        Util.ioPool().execute(() -> {
            try {
                Files.createDirectories(d);
                Path tmp = d.resolve(id + ".png.tmp");
                Files.write(tmp, png);
                Files.move(tmp, d.resolve(id + ".png"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                RpPda.LOGGER.error("RP PDA: не сохранить картинку учётки {}: {}", id, e.toString());
            }
        });
    }
}

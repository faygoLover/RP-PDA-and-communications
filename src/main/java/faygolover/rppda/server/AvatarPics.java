package faygolover.rppda.server;

import faygolover.rpcore.api.Pictures;
import faygolover.rpcore.api.event.PictureChangedEvent;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.network.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Своя картинка-аватарка учётки (§7.6) — через картинки по ключу RP Core ({@link Pictures}), ключ
 * {@code rppda:avatar/<ID учётки>}: так она есть и у NPC (у них нет игрока). Загрузка: окно просит → КПК проверяет
 * частоту ({@code avatar_upload_seconds}) и разрешает в Core ровно 64×64 → клиент шлёт PNG в Core → Core проверил и
 * сохранил → {@link PictureChangedEvent} → учётка получает вид «Картинка».
 */
public final class AvatarPics {
    private AvatarPics() {}

    public static final int SIZE = 64;
    private static final String PREFIX = "rppda:avatar/";

    private static final Map<UUID, Long> lastUpload = new HashMap<>();
    /** Игрок → учётка, чью картинку ему разрешили загрузить. */
    private static final Map<UUID, Integer> pending = new HashMap<>();

    public static String key(int account) {
        return PREFIX + account;
    }

    static void reset() {
        lastUpload.clear();
        pending.clear();
    }

    static void forget(UUID player) {
        lastUpload.remove(player);
        pending.remove(player);
    }

    /** Сколько секунд игроку ждать до следующей загрузки картинки (0 — можно сейчас). */
    static int waitSeconds(ServerPlayer sp) {
        Long last = lastUpload.get(sp.getUUID());
        long left = last == null ? 0 : PdaConfig.avatarUploadSeconds * 1000L - (System.currentTimeMillis() - last);
        return left <= 0 ? 0 : (int) (left / 1000 + 1);
    }

    /** Окно учётки {@code me} хочет загрузить картинку: разрешить в Core и сказать клиенту, куда слать. */
    static void begin(ServerPlayer sp, Account me) {
        long now = System.currentTimeMillis();
        Long last = lastUpload.get(sp.getUUID());
        long wait = PdaConfig.avatarUploadSeconds * 1000L;
        if (last != null && now - last < wait) {
            Actions.toast(sp, "rppda.err.avatar_too_soon", true, Long.toString((wait - (now - last)) / 1000 + 1));
            return;
        }
        lastUpload.put(sp.getUUID(), now);
        pending.put(sp.getUUID(), me.id);
        Pictures.allow(sp, key(me.id), SIZE, SIZE);
        CompoundTag t = new CompoundTag();
        t.putString("key", key(me.id));
        Net.send(sp, "avatar.upload", t);
    }

    /** Core принял картинку: если это разрешённая нами — учётке вид «Картинка». */
    static void onChanged(PictureChangedEvent e) {
        ServerPlayer sp = e.getUploader();
        PdaStore store = PdaServer.store;
        if (sp == null || store == null || !e.getKey().startsWith(PREFIX)) return;
        Integer acc = pending.get(sp.getUUID());
        if (acc == null || !e.getKey().equals(key(acc))) return;
        pending.remove(sp.getUUID());
        Account a = store.account(acc);
        if (a == null) return;
        a.avatarPic++;
        a.avatar = Account.AV_PICTURE;
        store.markAccounts();
        Actions.toast(sp, "rppda.toast.avatar_saved", false);
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        if (s != null && s.acting == a.id && s.mode == PdaServer.Session.Mode.OPEN) Views.sendState(sp, s);
    }

    /** ГМ: картинку — вон (у всех клиентов тоже); вид — «Лицо», если стояла картинка. */
    static void remove(Account a) {
        if (a.avatarPic == 0) return;
        a.avatarPic = 0;
        if (a.avatar == Account.AV_PICTURE) a.avatar = Account.AV_FACE;
        if (PdaServer.server != null) Pictures.remove(PdaServer.server, key(a.id));
    }
}

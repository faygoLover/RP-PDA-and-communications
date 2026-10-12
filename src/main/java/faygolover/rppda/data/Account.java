package faygolover.rppda.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Учётка КПК. ID — 8 цифр (у Системы — 0). Имя у учётки игрока следует за его отображаемым именем (fakename),
 * у NPC его задаёт ГМ. Пароль — хэш с солью ({@link faygolover.rppda.util.Passwords}).
 */
public final class Account {
    public enum Kind { PLAYER, NPC, SYSTEM }

    /** Уведомления по чату: звук и счётчик, без звука (только счётчик на HUD), без уведомлений. */
    public enum Notify { SOUND, SILENT, NONE }

    public final int id;
    public final Kind kind;
    public String name;
    @Nullable
    public UUID owner;
    public int issue = 1;
    @Nullable
    public String pwHash;
    @Nullable
    public String pwSalt;
    public boolean locked;
    public boolean silent;
    /** Внешний вид КПК (задаёт ГМ). */
    public Look look = new Look();
    /** ГМ выключил связь на этом КПК; с какого момента (сообщения позже не видны, пока связь не вернут). */
    public boolean noSignal;
    public long noSignalSince;
    public long created;
    /** Свои подписи к контактам (пустая — без подписи). */
    public final LinkedHashMap<Integer, String> contacts = new LinkedHashMap<>();
    public final Set<Integer> blocked = new HashSet<>();
    /** До какого номера прочитан чат. */
    public final Map<Integer, Integer> lastRead = new HashMap<>();
    public final Map<Integer, Notify> notify = new HashMap<>();
    /** Чаты, которые выводятся и в чат игры (по умолчанию — только КПК). */
    public final Set<Integer> chatOut = new HashSet<>();
    public final java.util.List<Note> notes = new java.util.ArrayList<>();
    public int nextNote = 1;
    /** Учётку удалил ГМ: КПК с ней не работает, данных нет. */
    public boolean deleted;
    /** Приглашения в группы (ID чатов). */
    public final Set<Integer> invites = new HashSet<>();
    /** Длина пароля (для сложности взлома; сам пароль не хранится), 0 — неизвестна. */
    public int pwLen;
    /** Что увидит владелец при входе: 0 — ничего, 1 — «пытались взломать», 2 — «взломали»; когда. */
    public int hackNotice;
    public long hackTime;
    /** Попытки взлома кончились: эта выдача КПК мертва до перевыпуска ГМом. */
    public boolean burned;
    /**
     * Аватарка: вид ({@link #AV_FACE} … {@link #AV_PICTURE}), цвет из палитры (−1 — по ID), 1–2 знака (пусто — буква
     * имени); номер своей картинки (0 — нет; растёт с каждой загрузкой, клиенты по нему понимают, что она сменилась).
     */
    public int avatar = AV_FACE;
    public int avatarColor = -1;
    public String avatarMark = "";
    public int avatarPic;

    /** Лицо по скину, пока в сети; знаки на цвете; портрет (фото RP Core, виден и без сети); своя картинка. */
    public static final int AV_FACE = 0, AV_LETTER = 1, AV_PORTRAIT = 2, AV_PICTURE = 3;
    public static final int AV_COLORS = 12;

    public Account(int id, Kind kind, String name) {
        this.id = id;
        this.kind = kind;
        this.name = name;
    }

    public boolean hasPassword() {
        return pwHash != null;
    }

    public Notify notify(int chat) {
        return notify.getOrDefault(chat, Notify.SOUND);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("kind", kind.name());
        t.putString("name", name);
        if (owner != null) t.putUUID("owner", owner);
        t.putInt("issue", issue);
        if (pwHash != null && pwSalt != null) {
            t.putString("pw", pwHash);
            t.putString("salt", pwSalt);
        }
        t.putBoolean("locked", locked);
        t.putBoolean("silent", silent);
        t.put("look", look.save());
        if (noSignal) t.putLong("noSignalSince", noSignalSince);
        t.putLong("created", created);
        ListTag c = new ListTag();
        contacts.forEach((k, v) -> {
            CompoundTag e = new CompoundTag();
            e.putInt("id", k);
            e.putString("label", v);
            c.add(e);
        });
        t.put("contacts", c);
        t.put("blocked", new IntArrayTag(blocked.stream().mapToInt(Integer::intValue).toArray()));
        CompoundTag r = new CompoundTag();
        lastRead.forEach((k, v) -> r.putInt(Integer.toString(k), v));
        t.put("read", r);
        CompoundTag n = new CompoundTag();
        notify.forEach((k, v) -> n.putByte(Integer.toString(k), (byte) v.ordinal()));
        t.put("ntf", n);
        t.put("out", new IntArrayTag(chatOut.stream().mapToInt(Integer::intValue).toArray()));
        ListTag nl = new ListTag();
        for (Note note : notes) nl.add(note.save());
        t.put("notes", nl);
        t.putInt("nextNote", nextNote);
        if (deleted) t.putBoolean("deleted", true);
        t.put("invites", new IntArrayTag(invites.stream().mapToInt(Integer::intValue).toArray()));
        if (pwLen > 0) t.putInt("pwLen", pwLen);
        if (hackNotice > 0) {
            t.putInt("hackNotice", hackNotice);
            t.putLong("hackTime", hackTime);
        }
        if (burned) t.putBoolean("burned", true);
        if (avatar != AV_FACE) t.putInt("av", avatar);
        if (avatarColor >= 0) t.putInt("avColor", avatarColor);
        if (!avatarMark.isEmpty()) t.putString("avMark", avatarMark);
        if (avatarPic > 0) t.putInt("avPic", avatarPic);
        return t;
    }

    public static Account load(CompoundTag t) {
        Kind kind;
        try {
            kind = Kind.valueOf(t.getString("kind"));
        } catch (IllegalArgumentException e) {
            kind = Kind.NPC;
        }
        Account a = new Account(t.getInt("id"), kind, t.getString("name"));
        if (t.hasUUID("owner")) a.owner = t.getUUID("owner");
        a.issue = Math.max(1, t.getInt("issue"));
        if (t.contains("pw")) {
            a.pwHash = t.getString("pw");
            a.pwSalt = t.getString("salt");
        }
        a.locked = t.getBoolean("locked");
        a.silent = t.getBoolean("silent");
        a.look = Look.load(t.getCompound("look"));
        if (t.contains("noSignalSince")) {
            a.noSignal = true;
            a.noSignalSince = t.getLong("noSignalSince");
        }
        a.created = t.getLong("created");
        for (Tag e : t.getList("contacts", Tag.TAG_COMPOUND)) {
            CompoundTag ct = (CompoundTag) e;
            a.contacts.put(ct.getInt("id"), ct.getString("label"));
        }
        for (int b : t.getIntArray("blocked")) a.blocked.add(b);
        CompoundTag r = t.getCompound("read");
        for (String k : r.getAllKeys()) a.lastRead.put(Integer.parseInt(k), r.getInt(k));
        CompoundTag n = t.getCompound("ntf");
        Notify[] modes = Notify.values();
        for (String k : n.getAllKeys()) {
            int m = n.getByte(k);
            if (m >= 0 && m < modes.length) a.notify.put(Integer.parseInt(k), modes[m]);
        }
        for (int i : t.getIntArray("out")) a.chatOut.add(i);
        for (Tag e : t.getList("notes", Tag.TAG_COMPOUND)) a.notes.add(Note.load((CompoundTag) e));
        a.nextNote = Math.max(1, t.getInt("nextNote"));
        a.deleted = t.getBoolean("deleted");
        for (int i : t.getIntArray("invites")) a.invites.add(i);
        a.pwLen = t.getInt("pwLen");
        a.hackNotice = t.getInt("hackNotice");
        a.hackTime = t.getLong("hackTime");
        a.burned = t.getBoolean("burned");
        a.avatar = t.getInt("av");
        a.avatarColor = t.contains("avColor") ? t.getInt("avColor") : -1;
        a.avatarMark = t.getString("avMark");
        a.avatarPic = t.getInt("avPic");
        return a;
    }
}

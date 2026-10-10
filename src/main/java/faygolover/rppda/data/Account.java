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

    /** Как показывать сообщения чата: в КПК и в чате игры, только в КПК, без уведомлений. */
    public enum Notify { CHAT, PDA, MUTED }

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
    public long created;
    /** Свои подписи к контактам (пустая — без подписи). */
    public final LinkedHashMap<Integer, String> contacts = new LinkedHashMap<>();
    public final Set<Integer> blocked = new HashSet<>();
    /** До какого номера прочитан чат. */
    public final Map<Integer, Integer> lastRead = new HashMap<>();
    public final Map<Integer, Notify> notify = new HashMap<>();
    /** Приглашения в группы (ID чатов). */
    public final Set<Integer> invites = new HashSet<>();

    public Account(int id, Kind kind, String name) {
        this.id = id;
        this.kind = kind;
        this.name = name;
    }

    public boolean hasPassword() {
        return pwHash != null;
    }

    public Notify notify(int chat) {
        return notify.getOrDefault(chat, Notify.CHAT);
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
        t.put("notify", n);
        t.put("invites", new IntArrayTag(invites.stream().mapToInt(Integer::intValue).toArray()));
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
        a.created = t.getLong("created");
        for (Tag e : t.getList("contacts", Tag.TAG_COMPOUND)) {
            CompoundTag ct = (CompoundTag) e;
            a.contacts.put(ct.getInt("id"), ct.getString("label"));
        }
        for (int b : t.getIntArray("blocked")) a.blocked.add(b);
        CompoundTag r = t.getCompound("read");
        for (String k : r.getAllKeys()) a.lastRead.put(Integer.parseInt(k), r.getInt(k));
        CompoundTag n = t.getCompound("notify");
        Notify[] modes = Notify.values();
        for (String k : n.getAllKeys()) {
            int m = n.getByte(k);
            if (m >= 0 && m < modes.length) a.notify.put(Integer.parseInt(k), modes[m]);
        }
        for (int i : t.getIntArray("invites")) a.invites.add(i);
        return a;
    }
}

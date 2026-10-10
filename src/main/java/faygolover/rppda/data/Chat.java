package faygolover.rppda.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Чат: общий, личный диалог двух учёток, группа или канал (пишет только админ). Сообщения — в {@link ChatLog}. */
public final class Chat {
    public enum Type { GLOBAL, DM, GROUP, CHANNEL }

    public static final int GLOBAL_ID = 1;

    public final int id;
    public final Type type;
    public String title = "";
    /** Участники в порядке вступления (у общего — пусто: в нём все). */
    public final LinkedHashSet<Integer> members = new LinkedHashSet<>();
    public int admin;
    public final Set<Integer> invited = new HashSet<>();
    public int lastSeq;
    public long lastTime;
    public int lastSender;
    public String lastPreview = "";
    /** Сколько кусков по {@link PdaStore#CHUNK} сообщений уже в архиве: в «свежих» — с {@code archived*CHUNK+1}. */
    public int archived;
    public long created;

    public Chat(int id, Type type) {
        this.id = id;
        this.type = type;
    }

    public boolean isGroup() {
        return type == Type.GROUP || type == Type.CHANNEL;
    }

    public boolean has(int acc) {
        return type == Type.GLOBAL || members.contains(acc);
    }

    /** Собеседник в личном диалоге. */
    public int peer(int acc) {
        for (int m : members) if (m != acc) return m;
        return acc;
    }

    public boolean canWrite(int acc) {
        if (!has(acc)) return false;
        return type != Type.CHANNEL || admin == acc;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("type", type.name());
        t.putString("title", title);
        t.put("members", new IntArrayTag(members.stream().mapToInt(Integer::intValue).toArray()));
        t.putInt("admin", admin);
        t.put("invited", new IntArrayTag(invited.stream().mapToInt(Integer::intValue).toArray()));
        t.putInt("lastSeq", lastSeq);
        t.putLong("lastTime", lastTime);
        t.putInt("lastSender", lastSender);
        t.putString("lastPreview", lastPreview);
        t.putInt("archived", archived);
        t.putLong("created", created);
        return t;
    }

    public static Chat load(CompoundTag t) {
        Type type;
        try {
            type = Type.valueOf(t.getString("type"));
        } catch (IllegalArgumentException e) {
            type = Type.GROUP;
        }
        Chat c = new Chat(t.getInt("id"), type);
        c.title = t.getString("title");
        for (int m : t.getIntArray("members")) c.members.add(m);
        c.admin = t.getInt("admin");
        for (int m : t.getIntArray("invited")) c.invited.add(m);
        c.lastSeq = t.getInt("lastSeq");
        c.lastTime = t.getLong("lastTime");
        c.lastSender = t.getInt("lastSender");
        c.lastPreview = t.getString("lastPreview");
        c.archived = t.getInt("archived");
        c.created = t.getLong("created");
        return c;
    }
}

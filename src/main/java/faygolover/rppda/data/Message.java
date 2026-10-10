package faygolover.rppda.data;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Сообщение чата. {@code seq} — порядковый номер в чате (с 1, без пропусков): по нему листаются страницы и
 * раскладываются архивные куски. {@code gm} — ник ГМа, если писал он через «Сменить аккаунт» (видят только админы).
 */
public final class Message {
    public static final int DELETED = 1;
    /** Служебная строка группы: «X пригласил Y» и т. п. Текст — ключ перевода, аргументы — через {@code \n}. */
    public static final int NOTICE = 2;
    /** Действие ({@code /me}). */
    public static final int ACTION = 4;

    public final int seq;
    public final int sender;
    public final long time;
    public final String text;
    public final int replyTo;
    public int flags;
    @Nullable
    public final String gm;

    public Message(int seq, int sender, long time, String text, int replyTo, int flags, @Nullable String gm) {
        this.seq = seq;
        this.sender = sender;
        this.time = time;
        this.text = text;
        this.replyTo = replyTo;
        this.flags = flags;
        this.gm = gm;
    }

    public boolean deleted() {
        return (flags & DELETED) != 0;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("s", seq);
        t.putInt("f", sender);
        t.putLong("t", time);
        t.putString("x", text);
        if (replyTo > 0) t.putInt("r", replyTo);
        if (flags != 0) t.putInt("fl", flags);
        if (gm != null) t.putString("g", gm);
        return t;
    }

    public static Message load(CompoundTag t) {
        return new Message(t.getInt("s"), t.getInt("f"), t.getLong("t"), t.getString("x"), t.getInt("r"), t.getInt("fl"),
                t.contains("g") ? t.getString("g") : null);
    }
}

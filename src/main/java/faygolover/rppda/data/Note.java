package faygolover.rppda.data;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/** Заметка учётки. {@code from} — от кого получена (заметкой поделились в чате), иначе своя. */
public final class Note {
    public static final int TITLE_MAX = 40;
    public static final int TEXT_MAX = 2000;
    public static final int LIMIT = 50;

    public final int id;
    public String title;
    public String text;
    public long created;
    public long updated;
    @Nullable
    public String from;

    public Note(int id, String title, String text) {
        this.id = id;
        this.title = title;
        this.text = text;
    }

    /** Заголовок по умолчанию — первая строка текста. */
    public static String firstLine(String text) {
        String s = text.strip();
        int nl = s.indexOf('\n');
        s = nl < 0 ? s : s.substring(0, nl);
        return s.isEmpty() ? "…" : s;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("title", title);
        t.putString("text", text);
        t.putLong("created", created);
        t.putLong("updated", updated);
        if (from != null) t.putString("from", from);
        return t;
    }

    public static Note load(CompoundTag t) {
        Note n = new Note(t.getInt("id"), t.getString("title"), t.getString("text"));
        n.created = t.getLong("created");
        n.updated = t.getLong("updated");
        if (t.contains("from")) n.from = t.getString("from");
        return n;
    }
}

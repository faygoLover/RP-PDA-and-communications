package faygolover.rppda.data;

import java.util.ArrayList;
import java.util.List;

/** «Свежие» сообщения одного чата в памяти сервера (по возрастанию номера). */
public final class ChatLog {
    public final int chatId;
    public final List<Message> messages = new ArrayList<>();
    public boolean dirty;
    public long lastAccess;

    public ChatLog(int chatId) {
        this.chatId = chatId;
    }

    public Message find(int seq) {
        int lo = 0, hi = messages.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int s = messages.get(mid).seq;
            if (s == seq) return messages.get(mid);
            if (s < seq) lo = mid + 1;
            else hi = mid - 1;
        }
        return null;
    }

    /** Сообщения с номерами {@code from..to} включительно, что есть в «свежих». */
    public List<Message> range(int from, int to) {
        List<Message> out = new ArrayList<>();
        for (Message m : messages) {
            if (m.seq > to) break;
            if (m.seq >= from) out.add(m);
        }
        return out;
    }

    public int firstSeq() {
        return messages.isEmpty() ? Integer.MAX_VALUE : messages.get(0).seq;
    }
}

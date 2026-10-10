package faygolover.rppda.data;

import faygolover.rppda.util.Passwords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PdaStoreTest {
    @TempDir
    Path dir;

    private PdaStore fresh() {
        PdaStore s = new PdaStore(dir, LoggerFactory.getLogger("test"));
        s.load();
        return s;
    }

    @Test
    void accountsAndChatsSurviveRestart() {
        PdaStore s = fresh();
        UUID owner = UUID.randomUUID();
        Account a = s.createAccount(Account.Kind.PLAYER, "Док", owner);
        Account b = s.createAccount(Account.Kind.NPC, "Барыга", null);
        assertTrue(a.id >= 10_000_000 && a.id <= 99_999_999);
        a.contacts.put(b.id, "с рынка");
        a.blocked.add(12345678);
        a.notify.put(Chat.GLOBAL_ID, Account.Notify.MUTED);
        a.pwSalt = Passwords.newSalt();
        a.pwHash = Passwords.hash("секрет", a.pwSalt);
        s.markAccounts();
        Chat dm = s.dm(a.id, b.id);
        assertSame(dm, s.dm(b.id, a.id), "один диалог на пару в обе стороны");
        s.append(dm, a.id, "привет", 0, 0, null, 1000);
        s.append(dm, b.id, "здорово", 1, 0, "GameMaster", 2000);
        Chat g = s.createGroup(a.id, "Отряд", false);
        s.addMember(g, b.id);
        s.save();

        PdaStore r = fresh();
        Account a2 = r.account(a.id);
        assertNotNull(a2);
        assertEquals("Док", a2.name);
        assertEquals(owner, a2.owner);
        assertEquals("с рынка", a2.contacts.get(b.id));
        assertTrue(a2.blocked.contains(12345678));
        assertEquals(Account.Notify.MUTED, a2.notify(Chat.GLOBAL_ID));
        assertTrue(Passwords.check("секрет", a2.pwSalt, a2.pwHash));
        assertFalse(Passwords.check("неверно", a2.pwSalt, a2.pwHash));
        Chat dm2 = r.findDm(b.id, a.id);
        assertNotNull(dm2);
        List<Message> msgs = r.log(dm2).range(1, 10);
        assertEquals(2, msgs.size());
        assertEquals("здорово", msgs.get(1).text);
        assertEquals(1, msgs.get(1).replyTo);
        assertEquals("GameMaster", msgs.get(1).gm);
        assertTrue(r.chatsOf(b.id).contains(g.id), "индекс чатов восстанавливается");
        assertEquals(a.id, r.chat(g.id).admin);
        assertNotNull(r.account(0), "Система есть всегда");
    }

    @Test
    void oldMessagesGoToArchiveInWholeChunks() {
        PdaStore s = fresh();
        Account a = s.createAccount(Account.Kind.PLAYER, "A", UUID.randomUUID());
        Chat c = s.global();
        for (int i = 1; i <= 2500; i++) s.append(c, a.id, "m" + i, 0, 0, null, i);
        assertEquals(1, s.archiveOld(), "2500 сообщений: в архив уходит один кусок (1..1000), свежих остаётся 1500");
        assertEquals(1, c.archived);
        assertEquals(1001, PdaStore.hotFrom(c));
        assertEquals(1001, s.log(c).firstSeq());
        assertEquals(0, s.archiveOld(), "повторно — ничего");
        s.save();

        PdaStore r = fresh();
        Chat c2 = r.global();
        assertEquals(2500, c2.lastSeq);
        List<Message> chunk = r.readChunk(c2.id, 0);
        assertEquals(1000, chunk.size());
        assertEquals("m1", chunk.get(0).text);
        assertEquals("m1000", chunk.get(999).text);
        assertTrue(Files.isRegularFile(dir.resolve("archive").resolve("1").resolve("0.dat")));
        assertEquals(1500, r.log(c2).messages.size());
    }

    @Test
    void newAccountDoesNotSeeOldGlobalAsUnread() {
        PdaStore s = fresh();
        Account a = s.createAccount(Account.Kind.PLAYER, "A", UUID.randomUUID());
        for (int i = 0; i < 5; i++) s.append(s.global(), a.id, "x", 0, 0, null, i);
        Account b = s.createAccount(Account.Kind.PLAYER, "B", UUID.randomUUID());
        assertEquals(5, b.lastRead.get(Chat.GLOBAL_ID));
    }

    @Test
    void groupAdminPassesOnLeave() {
        PdaStore s = fresh();
        Account a = s.createAccount(Account.Kind.PLAYER, "A", UUID.randomUUID());
        Account b = s.createAccount(Account.Kind.PLAYER, "B", UUID.randomUUID());
        Chat g = s.createGroup(a.id, "G", true);
        assertTrue(g.canWrite(a.id));
        s.addMember(g, b.id);
        assertFalse(g.canWrite(b.id), "в канале пишет только админ");
        s.removeMember(g, a.id);
        assertEquals(b.id, g.admin);
        assertTrue(g.canWrite(b.id));
        assertFalse(s.chatsOf(a.id).contains(g.id));
    }

    @Test
    void pendingKeepsOnlyLimit() {
        PdaStore s = fresh();
        UUID p = UUID.randomUUID(), admin = UUID.randomUUID();
        for (int i = 0; i < 120; i++) s.addPending(p, new PdaStore.Pending(admin, i, "t" + i), 100);
        assertEquals(100, s.pending.get(p).size());
        assertEquals("t20", s.pending.get(p).peekFirst().text());
    }
}

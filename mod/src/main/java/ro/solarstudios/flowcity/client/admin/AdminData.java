package ro.solarstudios.flowcity.client.admin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.PacketBuffer;

/** Modele de date primite de la server + decodare din PacketBuffer. */
public final class AdminData {
    private AdminData() {}

    public static final class PlayerEntry {
        public String uuid;
        public String name;
        public int id;
        public boolean online;
        public long lastSeen;
        public boolean banned;
        public boolean muted;
    }

    public static final class PlayerList {
        public int total;
        public int page;
        public final List<PlayerEntry> entries = new ArrayList<>();
    }

    public static final class Sanction {
        public int id;
        public int minutes;
        public String type;
        public String admin;
        public String reason;
        public long ts;
        public long expires;
        public boolean active;
        public boolean deleted;
    }

    public static final class Note {
        public int id;
        public String admin;
        public String text;
        public long ts;
    }

    public static final class Flag {
        public String severity;
        public String trigger;
        public String details;
        public long ts;
    }

    public static final class Profile {
        public String uuid;
        public String name;
        public int id;
        public boolean online;
        public long first;
        public long last;
        public long playSeconds;
        public String ip;
        public String balance;
        public boolean muted;
        public boolean banned;
        public int warns;
        public int mutes;
        public int kicks;
        public int bans;
        public final List<Sanction> sanctions = new ArrayList<>();
        public final List<Note> notes = new ArrayList<>();
        public final List<Flag> flags = new ArrayList<>();
    }

    public static final class LogRow {
        public long ts;
        public String a;
        public String b;
        public String c;
    }

    public static final class Logs {
        public String type;
        public int page;
        public int pages;
        public int total;
        public final List<LogRow> rows = new ArrayList<>();
    }

    // ------------------------------------------------------------------ decodare

    public static PlayerList readPlayerList(PacketBuffer b) {
        PlayerList l = new PlayerList();
        l.total = b.readInt();
        l.page = b.readInt();
        int n = b.readInt();
        for (int i = 0; i < n; i++) {
            PlayerEntry e = new PlayerEntry();
            e.uuid = b.readString(64);
            e.name = b.readString(64);
            e.id = b.readInt();
            e.online = b.readBoolean();
            e.lastSeen = b.readLong();
            e.banned = b.readBoolean();
            e.muted = b.readBoolean();
            l.entries.add(e);
        }
        return l;
    }

    public static Profile readProfile(PacketBuffer b) {
        Profile p = new Profile();
        p.uuid = b.readString(64);
        p.name = b.readString(64);
        p.id = b.readInt();
        p.online = b.readBoolean();
        p.first = b.readLong();
        p.last = b.readLong();
        p.playSeconds = b.readLong();
        p.ip = b.readString(64);
        p.balance = b.readString(64);
        p.muted = b.readBoolean();
        p.banned = b.readBoolean();
        p.warns = b.readInt();
        p.mutes = b.readInt();
        p.kicks = b.readInt();
        p.bans = b.readInt();
        int ns = b.readInt();
        for (int i = 0; i < ns; i++) {
            Sanction s = new Sanction();
            s.id = b.readInt();
            s.type = b.readString(16);
            s.admin = b.readString(64);
            s.reason = b.readString(512);
            s.minutes = b.readInt();
            s.ts = b.readLong();
            s.expires = b.readLong();
            s.active = b.readBoolean();
            s.deleted = b.readBoolean();
            p.sanctions.add(s);
        }
        int nn = b.readInt();
        for (int i = 0; i < nn; i++) {
            Note n = new Note();
            n.id = b.readInt();
            n.admin = b.readString(64);
            n.text = b.readString(512);
            n.ts = b.readLong();
            p.notes.add(n);
        }
        int nf = b.readInt();
        for (int i = 0; i < nf; i++) {
            Flag f = new Flag();
            f.severity = b.readString(16);
            f.trigger = b.readString(64);
            f.details = b.readString(512);
            f.ts = b.readLong();
            p.flags.add(f);
        }
        return p;
    }

    public static Logs readLogs(PacketBuffer b) {
        Logs l = new Logs();
        l.type = b.readString(16);
        l.page = b.readInt();
        l.pages = b.readInt();
        l.total = b.readInt();
        int n = b.readInt();
        for (int i = 0; i < n; i++) {
            LogRow r = new LogRow();
            r.ts = b.readLong();
            r.a = b.readString(1024);
            r.b = b.readString(1024);
            r.c = b.readString(1024);
            l.rows.add(r);
        }
        return l;
    }
}

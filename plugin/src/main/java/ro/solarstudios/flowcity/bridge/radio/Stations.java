package ro.solarstudios.flowcity.bridge.radio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Logica statiilor radio personale, fara nicio dependenta de Bukkit (se testeaza direct). Tot ce schimba starea trece pe aici:
 * validari, limite, frecvente unice, parole (PBKDF2 cu sare), roluri (OWNER / CO-OWNER / MEMBER), invitatii cu expirare, transfer
 * automat al proprietatii cand ownerul lipseste prea mult, istoric. Nu e thread-safe: se foloseste doar din thread-ul principal;
 * pentru voce modulul publica separat un instantaneu imuabil.
 *
 * <p>Statiile oficiale (DEPARTMENT) sunt un sistem separat: vin din configuratie, au frecvente rezervate, nu pot fi create,
 * modificate sau inchise de jucatori, iar accesul la ele se verifica prin {@link Access} (LuckPerms + jobul din tura).
 */
public final class Stations {
    public enum Type {
        PUBLIC, PRIVATE, PASSWORD, GROUP, DEPARTMENT;

        public static Type parse(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public enum Role {
        OWNER, CO_OWNER, MEMBER
    }

    /** Verificarea accesului la o statie oficiala (permisiune LuckPerms sau job in tura) - implementata de modul. */
    public interface Access {
        boolean canUseOfficial(UUID player, Station official);
    }

    /** Evenimente pentru modul: notificari catre jucatori si reimprospatarea starii. */
    public static final class Event {
        public final String kind;
        public final long station;
        public final UUID who;
        public final String text;

        Event(String kind, long station, UUID who, String text) {
            this.kind = kind;
            this.station = station;
            this.who = who;
            this.text = text;
        }
    }

    public static final class Config {
        public int maxOwnedPerPlayer = 2;
        public int freqMinTenths = 1000;
        public int freqMaxTenths = 9999;
        public int maxMembersCap = 30;
        public int defaultMembers = 10;
        public int nameMin = 3;
        public int nameMax = 24;
        public int passMin = 4;
        public int passMax = 24;
        public long createCooldownMs = 30_000L;
        public long inviteCooldownMs = 3_000L;
        public long inviteTtlMs = 10 * 60_000L;
        public int maxInvitesPerStation = 20;
        public long ownerOfflineGraceMs = 10 * 60_000L;
        public int historyMax = 30;
        public int maxWrongPass = 5;
        public long wrongPassLockMs = 5 * 60_000L;
        public int pbkdfIterations = 20_000;
    }

    public static final class Station {
        public long id;
        public String name;
        public int freq;
        public Type type;
        public String salt = "";
        public String hash = "";
        public UUID owner;
        public final Set<UUID> coOwners = new LinkedHashSet<UUID>();
        /** Cei care au intrat in statie (persistat). Pentru statiile oficiale nu se tine lista. */
        public final Set<UUID> members = new LinkedHashSet<UUID>();
        public int maxMembers = 10;
        public long created;
        public long ownerOfflineSince;
        public boolean official;
        public String officialKey = "";
        /** Invitatii: tinta -> expirare; invitatorul in {@link #inviters}. */
        final Map<UUID, Long> invites = new LinkedHashMap<UUID, Long>();
        final Map<UUID, UUID> inviters = new HashMap<UUID, UUID>();

        public boolean locked() {
            return type == Type.PASSWORD;
        }

        public boolean hasPassword() {
            return !hash.isEmpty();
        }

        public Role roleOf(UUID u) {
            if (u == null) return null;
            if (u.equals(owner)) return Role.OWNER;
            if (coOwners.contains(u)) return Role.CO_OWNER;
            if (members.contains(u)) return Role.MEMBER;
            return null;
        }

        public boolean canManage(UUID u) {
            Role r = roleOf(u);
            return !official && (r == Role.OWNER || r == Role.CO_OWNER);
        }
    }

    public static final class Result {
        public final boolean ok;
        public final String msg;
        public final Station station;

        Result(boolean ok, String msg, Station st) {
            this.ok = ok;
            this.msg = msg;
            this.station = st;
        }

        static Result ok(String msg, Station st) {
            return new Result(true, msg, st);
        }

        static Result err(String msg) {
            return new Result(false, msg, null);
        }
    }

    public static final class HistoryEntry {
        public final long ts;
        public final String action;
        public final String station;
        public final int freq;

        HistoryEntry(long ts, String action, String station, int freq) {
            this.ts = ts;
            this.action = action;
            this.station = station;
            this.freq = freq;
        }
    }

    public final Config cfg;
    private final Map<Long, Station> byId = new LinkedHashMap<Long, Station>();
    private final Map<UUID, Long> active = new HashMap<UUID, Long>();
    private final Map<UUID, Long> lastCreate = new HashMap<UUID, Long>();
    private final Map<UUID, Long> lastInvite = new HashMap<UUID, Long>();
    private final Map<UUID, int[]> wrong = new HashMap<UUID, int[]>(); // {incercari}
    private final Map<UUID, Long> wrongLock = new HashMap<UUID, Long>();
    private final Map<UUID, List<HistoryEntry>> history = new HashMap<UUID, List<HistoryEntry>>();
    private final List<Event> events = new ArrayList<Event>();
    private final SecureRandom rnd = new SecureRandom();
    private long nextId = 1;
    private long nextOfficial = 1_000_000L;
    private boolean dirty;
    private Access access = (u, s) -> false;

    public Stations(Config cfg) {
        this.cfg = cfg;
    }

    public void setAccess(Access a) {
        this.access = a == null ? (u, s) -> false : a;
    }

    public boolean dirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    private void touch() {
        dirty = true;
    }

    // ------------------------------------------------------------------ frecvente

    /** "101.5" sau "101,5" -> 1015 (zecimi); -1 daca nu e valid. */
    public static int parseFreq(String s) {
        if (s == null) return -1;
        String t = s.trim().replace(',', '.');
        if (!t.matches("\\d{1,4}(\\.\\d)?")) return -1;
        try {
            return (int) Math.round(Double.parseDouble(t) * 10.0);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String fmtFreq(int tenths) {
        return (tenths / 10) + "." + (tenths % 10);
    }

    // ------------------------------------------------------------------ statii oficiale

    /** Inregistreaza (sau actualizeaza) o statie oficiala; frecventa ei devine rezervata. */
    public Station registerOfficial(String key, String name, int freqTenths) {
        for (Station s : byId.values()) {
            if (s.official && s.officialKey.equals(key)) {
                s.name = name;
                s.freq = freqTenths;
                return s;
            }
        }
        Station s = new Station();
        s.id = nextOfficial++;
        s.name = name;
        s.freq = freqTenths;
        s.type = Type.DEPARTMENT;
        s.official = true;
        s.officialKey = key;
        s.maxMembers = 9999;
        s.created = 0;
        byId.put(s.id, s);
        return s;
    }

    public void clearOfficial() {
        for (Iterator<Station> it = byId.values().iterator(); it.hasNext(); ) {
            Station s = it.next();
            if (s.official) {
                for (Iterator<Map.Entry<UUID, Long>> a = active.entrySet().iterator(); a.hasNext(); ) {
                    if (a.next().getValue() == s.id) a.remove();
                }
                it.remove();
            }
        }
        nextOfficial = 1_000_000L;
    }

    // ------------------------------------------------------------------ cautari

    public Station get(long id) {
        return byId.get(id);
    }

    public Station byFreq(int tenths) {
        for (Station s : byId.values()) if (s.freq == tenths) return s;
        return null;
    }

    public Station byOfficialKey(String key) {
        for (Station s : byId.values()) if (s.official && s.officialKey.equals(key)) return s;
        return null;
    }

    public List<Station> all() {
        return new ArrayList<Station>(byId.values());
    }

    public int ownedCount(UUID u) {
        int n = 0;
        for (Station s : byId.values()) if (!s.official && u.equals(s.owner)) n++;
        return n;
    }

    /** Statiile publice (PUBLIC) care contin {@code query} in nume (sau in frecventa), cele mai pline primele. */
    public List<Station> publicList(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Station> out = new ArrayList<Station>();
        for (Station s : byId.values()) {
            if (s.official || s.type != Type.PUBLIC) continue;
            if (!q.isEmpty() && !s.name.toLowerCase(Locale.ROOT).contains(q) && !fmtFreq(s.freq).contains(q)) continue;
            out.add(s);
        }
        Collections.sort(out, (a, b) -> b.members.size() != a.members.size() ? b.members.size() - a.members.size() : a.name.compareToIgnoreCase(b.name));
        return out.size() > limit ? new ArrayList<Station>(out.subList(0, limit)) : out;
    }

    /** Statiile personale in care jucatorul e membru (inclusiv cele pe care le detine). */
    public List<Station> joined(UUID u) {
        List<Station> out = new ArrayList<Station>();
        for (Station s : byId.values()) if (!s.official && s.roleOf(u) != null) out.add(s);
        return out;
    }

    public List<Station> owned(UUID u) {
        List<Station> out = new ArrayList<Station>();
        for (Station s : byId.values()) if (!s.official && s.canManage(u)) out.add(s);
        return out;
    }

    public List<Station> invitesFor(UUID u, long now) {
        List<Station> out = new ArrayList<Station>();
        for (Station s : byId.values()) {
            Long exp = s.invites.get(u);
            if (exp != null && exp > now) out.add(s);
        }
        return out;
    }

    public UUID inviterOf(Station s, UUID target) {
        return s.inviters.get(target);
    }

    public Station activeOf(UUID u) {
        Long id = active.get(u);
        return id == null ? null : byId.get(id);
    }

    /** Cine e acordat pe statie in acest moment (activ), dintre {@code candidates}. */
    public List<UUID> tunedIn(long stationId, Iterable<UUID> candidates) {
        List<UUID> out = new ArrayList<UUID>();
        for (UUID u : candidates) {
            Long a = active.get(u);
            if (a != null && a == stationId) out.add(u);
        }
        return out;
    }

    public Map<UUID, Long> activeMap() {
        return new HashMap<UUID, Long>(active);
    }

    public List<HistoryEntry> historyOf(UUID u) {
        List<HistoryEntry> h = history.get(u);
        return h == null ? new ArrayList<HistoryEntry>() : new ArrayList<HistoryEntry>(h);
    }

    public List<Event> drainEvents() {
        List<Event> out = new ArrayList<Event>(events);
        events.clear();
        return out;
    }

    private void ev(String kind, Station s, UUID who, String text) {
        events.add(new Event(kind, s == null ? 0 : s.id, who, text));
    }

    private void hist(UUID u, String action, Station s, long now) {
        if (u == null || s == null || s.official) return;
        List<HistoryEntry> h = history.get(u);
        if (h == null) {
            h = new ArrayList<HistoryEntry>();
            history.put(u, h);
        }
        h.add(0, new HistoryEntry(now, action, s.name, s.freq));
        while (h.size() > cfg.historyMax) h.remove(h.size() - 1);
    }

    // ------------------------------------------------------------------ validari

    static boolean validName(String n, int min, int max) {
        if (n == null) return false;
        String t = n.trim();
        if (t.length() < min || t.length() > max) return false;
        return t.matches("[\\p{L}\\p{N} _.'!-]+") && !t.matches(".*\\s{2,}.*");
    }

    private String nameCheck(String name, long exceptId) {
        if (!validName(name, cfg.nameMin, cfg.nameMax)) {
            return "Numele trebuie sa aiba " + cfg.nameMin + "-" + cfg.nameMax + " caractere (litere, cifre, spatiu, . _ - ! ').";
        }
        String t = name.trim();
        for (Station s : byId.values()) {
            if (s.id != exceptId && s.name.equalsIgnoreCase(t)) return "Exista deja o statie cu acest nume.";
        }
        return null;
    }

    private String freqCheck(int f, long exceptId) {
        if (f < 0) return "Frecventa trebuie sa fie un numar ca 123.4.";
        if (f < cfg.freqMinTenths || f > cfg.freqMaxTenths) {
            return "Frecventa trebuie sa fie intre " + fmtFreq(cfg.freqMinTenths) + " si " + fmtFreq(cfg.freqMaxTenths) + ".";
        }
        Station u = byFreq(f);
        if (u != null && u.id != exceptId) return u.official ? "Frecventa " + fmtFreq(f) + " e rezervata unui departament." : "Frecventa " + fmtFreq(f) + " e deja ocupata.";
        return null;
    }

    private String passCheck(String p) {
        if (p == null || p.length() < cfg.passMin || p.length() > cfg.passMax) return "Parola trebuie sa aiba " + cfg.passMin + "-" + cfg.passMax + " caractere.";
        return null;
    }

    // ------------------------------------------------------------------ parole

    private String newSalt() {
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        return hex(b);
    }

    String hashPass(String pass, String saltHex) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), unhex(saltHex), cfg.pbkdfIterations, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return hex(h);
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 indisponibil", e);
        }
    }

    boolean passOk(Station s, String pass) {
        if (!s.hasPassword()) return true;
        if (pass == null) return false;
        byte[] a = hashPass(pass, s.salt).getBytes(StandardCharsets.UTF_8);
        byte[] b = s.hash.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private void setPass(Station s, String pass) {
        if (pass == null || pass.isEmpty()) {
            s.salt = "";
            s.hash = "";
        } else {
            s.salt = newSalt();
            s.hash = hashPass(pass, s.salt);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    // ------------------------------------------------------------------ creare

    public Result create(UUID by, String name, int freq, Type type, String password, int maxMembers, long now) {
        if (type == null || type == Type.DEPARTMENT) return Result.err("Tip de statie invalid.");
        Long last = lastCreate.get(by);
        if (last != null && now - last < cfg.createCooldownMs) {
            return Result.err("Asteapta " + ((cfg.createCooldownMs - (now - last)) / 1000 + 1) + " s inainte sa creezi alta statie.");
        }
        if (ownedCount(by) >= cfg.maxOwnedPerPlayer) return Result.err("Poti avea cel mult " + cfg.maxOwnedPerPlayer + " statii create.");
        String e = nameCheck(name, 0);
        if (e == null) e = freqCheck(freq, 0);
        if (e == null && type == Type.PASSWORD) e = passCheck(password);
        if (e != null) return Result.err(e);
        int max = Math.max(2, Math.min(cfg.maxMembersCap, maxMembers <= 0 ? cfg.defaultMembers : maxMembers));
        Station s = new Station();
        s.id = nextId++;
        s.name = name.trim();
        s.freq = freq;
        s.type = type;
        s.owner = by;
        s.members.add(by);
        s.maxMembers = max;
        s.created = now;
        if (type == Type.PASSWORD) setPass(s, password);
        byId.put(s.id, s);
        lastCreate.put(by, now);
        active.put(by, s.id);
        hist(by, "CREATE", s, now);
        ev("created", s, by, "");
        touch();
        return Result.ok("Statia " + s.name + " (" + fmtFreq(s.freq) + ") a fost creata.", s);
    }

    // ------------------------------------------------------------------ intrare / iesire

    private boolean locked(UUID u, long now) {
        Long l = wrongLock.get(u);
        if (l == null) return false;
        if (l <= now) {
            wrongLock.remove(u);
            wrong.remove(u);
            return false;
        }
        return true;
    }

    /** Intra pe statie (si o activeaza). Parola se cere doar la PASSWORD, daca nu esti deja membru sau invitat. */
    public Result join(UUID u, long id, String password, long now) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (s.official) {
            if (!access.canUseOfficial(u, s)) return Result.err("Nu ai acces pe aceasta frecventa oficiala.");
            active.put(u, s.id);
            ev("tuned", s, u, "");
            return Result.ok("Esti pe " + s.name + " (" + fmtFreq(s.freq) + ").", s);
        }
        boolean member = s.roleOf(u) != null;
        Long inv = s.invites.get(u);
        boolean invited = inv != null && inv > now;
        if (!member) {
            if (s.members.size() >= s.maxMembers) return Result.err("Statia e plina (" + s.maxMembers + " membri).");
            switch (s.type) {
                case PUBLIC:
                    break;
                case PASSWORD:
                    if (!invited) {
                        if (locked(u, now)) return Result.err("Prea multe parole gresite. Incearca mai tarziu.");
                        if (!passOk(s, password)) {
                            int[] w = wrong.get(u);
                            if (w == null) {
                                w = new int[1];
                                wrong.put(u, w);
                            }
                            if (++w[0] >= cfg.maxWrongPass) wrongLock.put(u, now + cfg.wrongPassLockMs);
                            return Result.err("Parola gresita.");
                        }
                        wrong.remove(u);
                    }
                    break;
                default:
                    if (!invited) return Result.err("Statia e privata: ai nevoie de invitatie.");
            }
            s.members.add(u);
            s.invites.remove(u);
            s.inviters.remove(u);
            hist(u, "JOIN", s, now);
            ev("joined", s, u, "");
            touch();
        }
        active.put(u, s.id);
        return Result.ok("Esti conectat pe " + s.name + " (" + fmtFreq(s.freq) + ").", s);
    }

    public Result joinByFreq(UUID u, int freq, String password, long now) {
        Station s = byFreq(freq);
        if (s == null) return Result.err("Nicio statie pe frecventa " + fmtFreq(freq) + ".");
        return join(u, s.id, password, now);
    }

    /** Comuta pe o statie in care esti deja membru (sau oficiala permisa), fara parola. */
    public Result tune(UUID u, long id, long now) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (s.official ? !access.canUseOfficial(u, s) : s.roleOf(u) == null) return Result.err("Nu esti membru.");
        active.put(u, s.id);
        ev("tuned", s, u, "");
        return Result.ok("Esti pe " + s.name + " (" + fmtFreq(s.freq) + ").", s);
    }

    /** Se deconecteaza de pe statia activa (ramane membru). */
    public Result disconnect(UUID u) {
        Long a = active.remove(u);
        if (a == null) return Result.err("Nu esti conectat pe nicio statie.");
        Station s = byId.get(a);
        ev("tuned", s, u, "");
        return Result.ok("Te-ai deconectat.", s);
    }

    /** Parasesti statia (nu mai esti membru). Ownerul care pleaca da proprietatea mai departe sau inchide statia. */
    public Result leave(UUID u, long id, long now) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (s.official) return disconnect(u);
        if (s.roleOf(u) == null) return Result.err("Nu esti membru.");
        if (u.equals(s.owner)) {
            UUID next = successor(s, null);
            if (next == null) return close(u, id, now, true);
            transferInternal(s, next, now, "owner a plecat");
        }
        s.members.remove(u);
        s.coOwners.remove(u);
        dropActive(u, s.id);
        hist(u, "LEAVE", s, now);
        ev("left", s, u, "");
        touch();
        return Result.ok("Ai parasit statia " + s.name + ".", s);
    }

    private void dropActive(UUID u, long id) {
        Long a = active.get(u);
        if (a != null && a == id) active.remove(u);
    }

    private UUID successor(Station s, Predicate<UUID> online) {
        for (UUID c : s.coOwners) if (!c.equals(s.owner) && (online == null || online.test(c))) return c;
        for (UUID m : s.members) if (!m.equals(s.owner) && (online == null || online.test(m))) return m;
        return null;
    }

    // ------------------------------------------------------------------ invitatii

    public Result invite(UUID by, UUID target, long id, long now) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (!s.canManage(by)) return Result.err("Doar OWNER si CO-OWNER pot invita.");
        if (by.equals(target)) return Result.err("Nu te poti invita singur.");
        if (s.roleOf(target) != null) return Result.err("Jucatorul e deja membru.");
        Long last = lastInvite.get(by);
        if (last != null && now - last < cfg.inviteCooldownMs) return Result.err("Invitatii prea repede. Asteapta putin.");
        purgeInvites(s, now);
        if (s.invites.size() >= cfg.maxInvitesPerStation) return Result.err("Prea multe invitatii in asteptare.");
        if (s.members.size() >= s.maxMembers) return Result.err("Statia e plina.");
        s.invites.put(target, now + cfg.inviteTtlMs);
        s.inviters.put(target, by);
        lastInvite.put(by, now);
        ev("invited", s, target, "");
        touch();
        return Result.ok("Invitatie trimisa.", s);
    }

    private void purgeInvites(Station s, long now) {
        for (Iterator<Map.Entry<UUID, Long>> it = s.invites.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() <= now) {
                s.inviters.remove(e.getKey());
                it.remove();
            }
        }
    }

    public Result acceptInvite(UUID u, long id, long now) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        Long inv = s.invites.get(u);
        if (inv == null || inv <= now) return Result.err("Invitatia a expirat.");
        return join(u, id, null, now);
    }

    public Result declineInvite(UUID u, long id) {
        Station s = byId.get(id);
        if (s == null || s.invites.remove(u) == null) return Result.err("Nu exista invitatia.");
        s.inviters.remove(u);
        touch();
        ev("declined", s, u, "");
        return Result.ok("Invitatie refuzata.", s);
    }

    // ------------------------------------------------------------------ administrare (owner / co-owner)

    public Result kick(UUID by, UUID target, long id, long now) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (!s.canManage(by)) return Result.err("Nu ai dreptul sa elimini membri.");
        Role tr = s.roleOf(target);
        if (tr == null) return Result.err("Nu e membru.");
        if (tr == Role.OWNER) return Result.err("Ownerul nu poate fi eliminat.");
        if (tr == Role.CO_OWNER && s.roleOf(by) != Role.OWNER) return Result.err("Doar ownerul poate elimina un CO-OWNER.");
        s.members.remove(target);
        s.coOwners.remove(target);
        dropActive(target, s.id);
        hist(target, "KICKED", s, now);
        ev("kicked", s, target, "");
        touch();
        return Result.ok("Membru eliminat.", s);
    }

    public Result promote(UUID by, UUID target, long id) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (!by.equals(s.owner)) return Result.err("Doar ownerul poate promova.");
        if (s.roleOf(target) != Role.MEMBER) return Result.err("Poti promova doar un membru obisnuit.");
        s.coOwners.add(target);
        ev("role", s, target, "");
        touch();
        return Result.ok("Promovat CO-OWNER.", s);
    }

    public Result demote(UUID by, UUID target, long id) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (!by.equals(s.owner)) return Result.err("Doar ownerul poate retrograda.");
        if (!s.coOwners.remove(target)) return Result.err("Nu e CO-OWNER.");
        ev("role", s, target, "");
        touch();
        return Result.ok("Retrogradat la membru.", s);
    }

    public Result transfer(UUID by, UUID target, long id, long now) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (!by.equals(s.owner)) return Result.err("Doar ownerul poate transfera proprietatea.");
        if (by.equals(target) || s.roleOf(target) == null) return Result.err("Tinta trebuie sa fie alt membru al statiei.");
        if (ownedCount(target) >= cfg.maxOwnedPerPlayer) return Result.err("Acel jucator are deja numarul maxim de statii.");
        transferInternal(s, target, now, "transfer");
        return Result.ok("Proprietatea a fost transferata.", s);
    }

    private void transferInternal(Station s, UUID target, long now, String why) {
        UUID old = s.owner;
        s.coOwners.remove(target);
        s.owner = target;
        s.ownerOfflineSince = 0;
        if (old != null && s.members.contains(old) && !old.equals(target)) s.coOwners.add(old);
        hist(target, "OWNER", s, now);
        ev("owner", s, target, why);
        touch();
    }

    public Result rename(UUID by, long id, String name) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (!by.equals(s.owner) && !s.canManage(by)) return Result.err("Nu ai dreptul.");
        if (!by.equals(s.owner)) return Result.err("Doar ownerul schimba numele.");
        String e = nameCheck(name, s.id);
        if (e != null) return Result.err(e);
        s.name = name.trim();
        ev("changed", s, by, "");
        touch();
        return Result.ok("Nume schimbat.", s);
    }

    public Result setFreq(UUID by, long id, int freq) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (!by.equals(s.owner)) return Result.err("Doar ownerul schimba frecventa.");
        String e = freqCheck(freq, s.id);
        if (e != null) return Result.err(e);
        s.freq = freq;
        ev("changed", s, by, "");
        touch();
        return Result.ok("Frecventa schimbata la " + fmtFreq(freq) + ".", s);
    }

    /** Schimba parola; parola goala o scoate (statia PASSWORD devine PUBLIC). */
    public Result setPassword(UUID by, long id, String pass) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (!by.equals(s.owner)) return Result.err("Doar ownerul schimba parola.");
        if (pass == null || pass.isEmpty()) {
            setPass(s, "");
            if (s.type == Type.PASSWORD) s.type = Type.PUBLIC;
        } else {
            String e = passCheck(pass);
            if (e != null) return Result.err(e);
            setPass(s, pass);
            s.type = Type.PASSWORD;
        }
        ev("changed", s, by, "");
        touch();
        return Result.ok(s.type == Type.PASSWORD ? "Parola schimbata." : "Parola scoasa, statia e publica.", s);
    }

    public Result setType(UUID by, long id, Type type, String pass) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (!by.equals(s.owner)) return Result.err("Doar ownerul schimba tipul.");
        if (type == null || type == Type.DEPARTMENT) return Result.err("Tip invalid.");
        if (type == Type.PASSWORD) {
            String e = s.hasPassword() && (pass == null || pass.isEmpty()) ? null : passCheck(pass);
            if (e != null) return Result.err(e);
            if (pass != null && !pass.isEmpty()) setPass(s, pass);
        } else {
            setPass(s, "");
        }
        s.type = type;
        ev("changed", s, by, "");
        touch();
        return Result.ok("Tip schimbat: " + type + ".", s);
    }

    public Result setMax(UUID by, long id, int max) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (!by.equals(s.owner)) return Result.err("Doar ownerul schimba limita de membri.");
        int m = Math.max(2, Math.min(cfg.maxMembersCap, max));
        if (m < s.members.size()) return Result.err("Sunt deja " + s.members.size() + " membri.");
        s.maxMembers = m;
        ev("changed", s, by, "");
        touch();
        return Result.ok("Limita de membri: " + m + ".", s);
    }

    /** Inchide statia: ownerul, sau {@code force} pentru admin / ultimul membru care pleaca. */
    public Result close(UUID by, long id, long now, boolean force) {
        Station s = byId.get(id);
        if (s == null) return Result.err("Statia nu mai exista.");
        if (s.official) return Result.err("Statiile oficiale nu se pot inchide.");
        if (!force && !by.equals(s.owner)) return Result.err("Doar ownerul inchide statia.");
        byId.remove(id);
        for (UUID m : new ArrayList<UUID>(s.members)) {
            dropActive(m, id);
            if (!m.equals(by)) hist(m, "CLOSED", s, now);
        }
        for (Iterator<Map.Entry<UUID, Long>> it = active.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue() == id) it.remove();
        if (by != null) hist(by, "CLOSE", s, now);
        for (UUID m : s.members) ev("closed", s, m, "");
        for (UUID inv : s.invites.keySet()) ev("closed", s, inv, "");
        touch();
        return Result.ok("Statia " + s.name + " a fost inchisa.", s);
    }

    // ------------------------------------------------------------------ owner offline

    public void ownerOnline(UUID u) {
        for (Station s : byId.values()) {
            if (!s.official && u.equals(s.owner) && s.ownerOfflineSince != 0) {
                s.ownerOfflineSince = 0;
                touch();
            }
        }
    }

    public void ownerOffline(UUID u, long now) {
        for (Station s : byId.values()) {
            if (!s.official && u.equals(s.owner) && s.ownerOfflineSince == 0) {
                s.ownerOfflineSince = now;
                touch();
            }
        }
    }

    public void playerOffline(UUID u) {
        // activa ramane doar pentru cei online; la reconectare jucatorul alege din nou
        active.remove(u);
    }

    /**
     * Periodic: statiile al caror owner lipseste de peste perioada de gratie trec la un CO-OWNER / membru (preferat cel online);
     * fara succesor, se inchid. Curata si invitatiile expirate.
     */
    public void tick(long now, Predicate<UUID> online) {
        for (Station s : new ArrayList<Station>(byId.values())) {
            if (s.official) continue;
            purgeInvites(s, now);
            if (s.ownerOfflineSince != 0 && now - s.ownerOfflineSince >= cfg.ownerOfflineGraceMs) {
                UUID next = successor(s, online);
                if (next == null) next = successor(s, null);
                if (next != null && ownedCount(next) < cfg.maxOwnedPerPlayer) {
                    transferInternal(s, next, now, "owner absent");
                } else {
                    close(s.owner, s.id, now, true);
                }
            }
        }
    }

    // ------------------------------------------------------------------ persistenta

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("nextId", nextId);
        JsonArray arr = new JsonArray();
        for (Station s : byId.values()) {
            if (s.official) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id);
            o.addProperty("name", s.name);
            o.addProperty("freq", s.freq);
            o.addProperty("type", s.type.name());
            o.addProperty("salt", s.salt);
            o.addProperty("hash", s.hash);
            o.addProperty("owner", s.owner == null ? "" : s.owner.toString());
            o.addProperty("max", s.maxMembers);
            o.addProperty("created", s.created);
            o.addProperty("ownerOffline", s.ownerOfflineSince);
            o.add("coOwners", uuids(s.coOwners));
            o.add("members", uuids(s.members));
            JsonArray inv = new JsonArray();
            for (Map.Entry<UUID, Long> e : s.invites.entrySet()) {
                JsonObject i = new JsonObject();
                i.addProperty("to", e.getKey().toString());
                i.addProperty("until", e.getValue());
                UUID by = s.inviters.get(e.getKey());
                i.addProperty("by", by == null ? "" : by.toString());
                inv.add(i);
            }
            o.add("invites", inv);
            arr.add(o);
        }
        root.add("stations", arr);
        JsonObject hs = new JsonObject();
        for (Map.Entry<UUID, List<HistoryEntry>> e : history.entrySet()) {
            JsonArray ha = new JsonArray();
            for (HistoryEntry h : e.getValue()) {
                JsonObject o = new JsonObject();
                o.addProperty("ts", h.ts);
                o.addProperty("a", h.action);
                o.addProperty("n", h.station);
                o.addProperty("f", h.freq);
                ha.add(o);
            }
            hs.add(e.getKey().toString(), ha);
        }
        root.add("history", hs);
        return GSON.toJson(root);
    }

    private static JsonArray uuids(Set<UUID> s) {
        JsonArray a = new JsonArray();
        for (UUID u : s) a.add(u.toString());
        return a;
    }

    /** Incarca statiile personale (inlocuieste cele existente, pastreaza statiile oficiale). Date stricate se ignora, nu strica pornirea. */
    public void loadJson(String json) {
        List<Station> keep = new ArrayList<Station>();
        for (Station s : byId.values()) if (s.official) keep.add(s);
        byId.clear();
        for (Station s : keep) byId.put(s.id, s);
        history.clear();
        if (json == null || json.trim().isEmpty()) return;
        JsonObject root;
        try {
            root = new JsonParser().parse(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        if (root.has("nextId")) nextId = Math.max(1, root.get("nextId").getAsLong());
        if (root.has("stations")) {
            for (JsonElement el : root.getAsJsonArray("stations")) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    Station s = new Station();
                    s.id = o.get("id").getAsLong();
                    s.name = o.get("name").getAsString();
                    s.freq = o.get("freq").getAsInt();
                    s.type = Type.parse(o.get("type").getAsString());
                    if (s.type == null || s.type == Type.DEPARTMENT) s.type = Type.PRIVATE;
                    s.salt = o.has("salt") ? o.get("salt").getAsString() : "";
                    s.hash = o.has("hash") ? o.get("hash").getAsString() : "";
                    String ow = o.get("owner").getAsString();
                    s.owner = ow.isEmpty() ? null : UUID.fromString(ow);
                    s.maxMembers = o.get("max").getAsInt();
                    s.created = o.get("created").getAsLong();
                    s.ownerOfflineSince = o.has("ownerOffline") ? o.get("ownerOffline").getAsLong() : 0;
                    readUuids(o.getAsJsonArray("coOwners"), s.coOwners);
                    readUuids(o.getAsJsonArray("members"), s.members);
                    if (s.owner != null) s.members.add(s.owner);
                    if (o.has("invites")) {
                        for (JsonElement ie : o.getAsJsonArray("invites")) {
                            JsonObject i = ie.getAsJsonObject();
                            UUID to = UUID.fromString(i.get("to").getAsString());
                            s.invites.put(to, i.get("until").getAsLong());
                            String by = i.has("by") ? i.get("by").getAsString() : "";
                            if (!by.isEmpty()) s.inviters.put(to, UUID.fromString(by));
                        }
                    }
                    if (s.owner == null || byFreq(s.freq) != null) continue; // frecventa dubla sau fara owner: ignorata
                    byId.put(s.id, s);
                    nextId = Math.max(nextId, s.id + 1);
                } catch (RuntimeException ignored) {
                    // intrare stricata
                }
            }
        }
        if (root.has("history")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("history").entrySet()) {
                try {
                    UUID u = UUID.fromString(e.getKey());
                    List<HistoryEntry> l = new ArrayList<HistoryEntry>();
                    for (JsonElement he : e.getValue().getAsJsonArray()) {
                        JsonObject o = he.getAsJsonObject();
                        l.add(new HistoryEntry(o.get("ts").getAsLong(), o.get("a").getAsString(), o.get("n").getAsString(), o.get("f").getAsInt()));
                    }
                    history.put(u, l);
                } catch (RuntimeException ignored) {
                    // intrare stricata
                }
            }
        }
    }

    private static void readUuids(JsonArray a, Set<UUID> out) {
        if (a == null) return;
        for (JsonElement e : a) out.add(UUID.fromString(e.getAsString()));
    }

    /** Pentru teste si administrare: numarul de statii personale. */
    public int personalCount() {
        int n = 0;
        for (Station s : byId.values()) if (!s.official) n++;
        return n;
    }

    public Set<Long> ids() {
        return new HashSet<Long>(byId.keySet());
    }
}

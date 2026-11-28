package ro.solarstudios.flowcity.bridge.hype;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.hype.HypeDb.AtmEntry;
import ro.solarstudios.flowcity.bridge.hype.HypeDb.PlayerData;

/** ATM: depunere/retragere cash <-> banca si transfer bancar intre jucatori. Totul pe thread-ul principal (atomic). */
public final class AtmService {
    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private final FlowCityBridge plugin;
    private final HypeDb hdb;
    private final Set<String> atmBlocks = new HashSet<>();
    private YamlConfiguration cfg;

    public AtmService(FlowCityBridge plugin, HypeDb hdb, YamlConfiguration cfg) {
        this.plugin = plugin;
        this.hdb = hdb;
        this.cfg = cfg;
    }

    private java.util.function.Function<UUID, PlayerData> lookup = u -> null;

    public void setLookup(java.util.function.Function<UUID, PlayerData> f) {
        this.lookup = f == null ? u -> null : f;
    }

    /** Cardul jucatorului e blocat de titular? */
    public boolean cardBlocked(UUID u) {
        PlayerData d = lookup.apply(u);
        return d != null && d.cardBlocked;
    }

    /** Motivul refuzului cand cardOk() e fals: card blocat sau lipsa cardului. */
    public String cardMsg(Player p) {
        return cardBlocked(p.getUniqueId()) ? BLOCKED : NO_CARD;
    }

    public static final String BLOCKED = "Cardul tau este blocat. Il deblochezi din aplicatia FlowCity Bank (Card bancar).";

    /** Blocheaza/deblocheaza cardul titularului (doar el, pe server). */
    public Result setCardBlocked(Player p, PlayerData d, boolean blocked) {
        if (d.cardBlocked == blocked) return new Result(true, blocked ? "Cardul este deja blocat." : "Cardul este deja activ.");
        d.cardBlocked = blocked;
        hdb.setCardBlocked(p.getUniqueId(), blocked);
        plugin.service().logTransaction(p.getUniqueId(), blocked ? "CARD_BLOCK" : "CARD_UNBLOCK", 0, "card", where(p));
        return new Result(true, blocked ? "Card blocat. Nicio operatiune ATM nu mai e posibila pana il deblochezi." : "Card deblocat.");
    }

    /** Descrierea optionala a transferului: maxim 40 de caractere, fara coduri de culoare sau caractere de control. */
    static String cleanNote(String note) {
        if (note == null) return "";
        String n = note.replaceAll("[\\p{Cntrl}\u00a7]", "").trim();
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    /** Verifica un destinatar de transfer (nume exact, online sau care a mai jucat): intoarce numele corect sau null. */
    public String lookupName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 16) return null;
        OfflinePlayer t = Bukkit.getPlayerExact(name.trim());
        if (t == null) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(name.trim());
            if (off != null && (off.hasPlayedBefore() || off.isOnline())) t = off;
        }
        return t == null ? null : t.getName();
    }

    public void reload(YamlConfiguration cfg) {
        this.cfg = cfg;
    }

    public void loadBlocks() {
        hdb.loadAtmBlocks().whenComplete((rows, err) -> {
            if (rows == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                atmBlocks.clear();
                for (String[] r : rows) atmBlocks.add(PlacedSet.key(r[0], Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[3])));
            });
        });
    }

    // ------------------------------------------------------------------ ATM-uri fizice

    private static String key(Location l) {
        return PlacedSet.key(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public boolean isAtmBlock(Location l) {
        return l != null && l.getWorld() != null && atmBlocks.contains(key(l));
    }

    public boolean setAtmBlock(Location l, boolean present) {
        String k = key(l);
        boolean changed = present ? atmBlocks.add(k) : atmBlocks.remove(k);
        if (changed) hdb.setAtmBlock(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), present);
        return changed;
    }

    public int atmCount() {
        return atmBlocks.size();
    }

    public static final String NO_CARD = "Introdu cardul bancar! Ai nevoie de un card in inventar.";

    /** Cheia PDC cu proprietarul cardului (UUID). Se scrie la prima folosire, daca atm.card-bind e pornit. */
    private org.bukkit.NamespacedKey ownerKey;

    private org.bukkit.NamespacedKey ownerKey() {
        if (ownerKey == null) ownerKey = new org.bukkit.NamespacedKey(plugin, "card_owner");
        return ownerKey;
    }

    /** Legarea cardului de proprietar e pornita? (atm.card-bind, implicit da) */
    public boolean bindEnabled() {
        return cfg.getBoolean("atm.card-bind", true);
    }

    /** Proprietarul scris in card sau null daca nu e legat inca. */
    public java.util.UUID cardOwner(org.bukkit.inventory.ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer()
            .get(ownerKey(), org.bukkit.persistence.PersistentDataType.STRING);
        if (s == null || s.isEmpty()) return null;
        try {
            return java.util.UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Leaga cardul de jucator (prima folosire) si ii scrie numele in lore. */
    public void bindCard(Player p, org.bukkit.inventory.ItemStack it) {
        if (it == null || !bindEnabled()) return;
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        if (m == null) return;
        m.getPersistentDataContainer().set(ownerKey(), org.bukkit.persistence.PersistentDataType.STRING, p.getUniqueId().toString());
        java.util.List<String> lore = m.hasLore() ? m.getLore() : new java.util.ArrayList<>();
        if (lore == null) lore = new java.util.ArrayList<>();
        lore.removeIf(l -> l != null && l.contains("Titular:"));
        lore.add("\u00a77Titular: \u00a7f" + p.getName());
        m.setLore(lore);
        it.setItemMeta(m);
    }

    /**
     * Cardul din mana (sau din inventar) e al jucatorului? Daca nu e legat inca, il leaga acum.
     * Intoarce null daca totul e in regula, altfel motivul refuzului.
     */
    public String cardOwnerCheck(Player p) {
        if (!cardRequired(p) || !bindEnabled()) return null;
        org.bukkit.inventory.ItemStack free = null;
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents()) {
            if (!isCard(it)) continue;
            java.util.UUID o = cardOwner(it);
            if (o == null) {
                if (free == null) free = it;
            } else if (o.equals(p.getUniqueId())) {
                return null;
            }
        }
        org.bukkit.inventory.ItemStack off = p.getInventory().getItemInOffHand();
        if (isCard(off)) {
            java.util.UUID o = cardOwner(off);
            if (o == null) {
                if (free == null) free = off;
            } else if (o.equals(p.getUniqueId())) {
                return null;
            }
        }
        if (free != null) {
            bindCard(p, free);
            return null;
        }
        return "Cardul bancar apartine altui jucator.";
    }

    public static boolean isCard(org.bukkit.inventory.ItemStack it) {
        if (it == null || it.getType() == org.bukkit.Material.AIR) return false;
        String n = it.getType().name().toLowerCase(java.util.Locale.ROOT);
        String key = "";
        try {
            key = it.getType().getKey().toString().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException | LinkageError ignored) {
            // materialele modate pot lipsi din registru
        }
        return key.startsWith("flowcitycore:cardbancar") || n.contains("flowcitycore") && n.contains("cardbancar");
    }

    /** Cardul bancar e obligatoriu (atm.require-card, implicit da); staff-ul cu flowcity.hype.atm.nocard il ocoleste. */
    public boolean cardRequired(Player p) {
        return cfg.getBoolean("atm.require-card", true) && !p.hasPermission("flowcity.hype.atm.nocard");
    }

    /** Card in orice loc din inventar (pentru operatiile din ATM deja deschis si pentru ATM-ul din telefon). */
    public boolean cardOk(Player p) {
        if (cardBlocked(p.getUniqueId())) return false;
        if (!cardRequired(p)) return true;
        boolean any = isCard(p.getInventory().getItemInOffHand());
        if (!any) {
            for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents()) {
                if (isCard(it)) {
                    any = true;
                    break;
                }
            }
        }
        // cardul trebuie sa fie al jucatorului (daca atm.card-bind e pornit); unul nelegat se leaga acum
        return any && cardOwnerCheck(p) == null;
    }

    /** Card tinut in mana (cand dai click pe un ATM fizic: "introduci" cardul). */
    public boolean cardInHand(Player p) {
        if (!cardRequired(p)) return true;
        return isCard(p.getInventory().getItemInMainHand()) || isCard(p.getInventory().getItemInOffHand());
    }

    /** Poate jucatorul sa foloseasca ATM-ul acum (oriunde sau langa un ATM fizic)? */
    public boolean canUse(Player p) {
        if (cfg.getBoolean("atm.command-anywhere", true)) return true;
        double max = cfg.getDouble("atm.max-distance", 6);
        Location pl = p.getLocation();
        for (String k : atmBlocks) {
            String[] s = k.split(":");
            if (s.length != 4 || !s[0].equals(pl.getWorld().getName())) continue;
            double dx = Integer.parseInt(s[1]) + 0.5 - pl.getX();
            double dy = Integer.parseInt(s[2]) + 0.5 - pl.getY();
            double dz = Integer.parseInt(s[3]) + 0.5 - pl.getZ();
            if (dx * dx + dy * dy + dz * dz <= max * max) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ date pentru client

    public JsonObject data(Player p, PlayerData d) {
        rollDay(d);
        JsonObject o = new JsonObject();
        o.addProperty("cash", plugin.money().balance(p.getUniqueId()));
        o.addProperty("bank", plugin.bank().balance(p.getUniqueId()));
        o.addProperty("feePercent", cfg.getDouble("atm.fee-percent", 1.0));
        o.addProperty("feeMin", cfg.getDouble("atm.fee-min", 0.5));
        o.addProperty("minAmount", cfg.getDouble("atm.min-amount", 1.0));
        double dw = cfg.getDouble("atm.daily-withdraw-limit", 0);
        double dt = cfg.getDouble("atm.daily-transfer-limit", 0);
        o.addProperty("withdrawLeft", dw <= 0 ? -1 : Math.max(0, HypeMath.round2(dw - d.withdrawnToday)));
        o.addProperty("transferLeft", dt <= 0 ? -1 : Math.max(0, HypeMath.round2(dt - d.transferredToday)));
        JsonArray h = new JsonArray();
        for (AtmEntry e : d.history) {
            JsonObject j = new JsonObject();
            j.addProperty("t", e.type);
            j.addProperty("a", e.amount);
            j.addProperty("f", e.fee);
            j.addProperty("o", e.other);
            j.addProperty("ts", e.ts);
            j.addProperty("b", e.bal);
            h.add(j);
        }
        o.add("history", h);
        o.add("card", cardInfo(p, d));
        return o;
    }

    /**
     * Cardul pentru ecranul bancii: titular, ultimele 4 cifre (derivate din UUID, stabile; cardul nu are alt numar),
     * stare (ACTIV / BLOCAT / LIPSA) si daca e cerut la ATM. Nu exista PIN in sistem, deci nu se trimite nimic de acest fel.
     */
    private JsonObject cardInfo(Player p, PlayerData d) {
        JsonObject c = new JsonObject();
        c.addProperty("holder", p.getName());
        int h = Math.abs(p.getUniqueId().hashCode() % 10000);
        c.addProperty("last4", String.format("%04d", h));
        boolean has = isCard(p.getInventory().getItemInOffHand());
        if (!has) {
            for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents()) {
                if (isCard(it)) {
                    has = true;
                    break;
                }
            }
        }
        c.addProperty("inInventory", has);
        c.addProperty("required", cardRequired(p));
        c.addProperty("status", d.cardBlocked ? "BLOCAT" : (cardRequired(p) && !has ? "LIPSA" : "ACTIV"));
        return c;
    }

    private void rollDay(PlayerData d) {
        long today = HypeMath.dayNumber(System.currentTimeMillis());
        if (d.dayNumber != today) {
            d.dayNumber = today;
            d.withdrawnToday = 0;
            d.transferredToday = 0;
        }
    }

    private double max() {
        return cfg.getDouble("atm.max-per-transaction", 1000000);
    }

    private double min() {
        return cfg.getDouble("atm.min-amount", 1.0);
    }

    private void record(PlayerData d, UUID u, String type, double amount, double fee, String other) {
        long now = System.currentTimeMillis();
        double bal = plugin.bank().balance(u);
        d.history.add(0, new AtmEntry(type, amount, fee, other, now, bal));
        while (d.history.size() > 100) d.history.remove(d.history.size() - 1);
        hdb.addTx(u, type, amount, fee, other, now, bal);
    }

    private static String where(Player p) {
        Location l = p.getLocation();
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    // ------------------------------------------------------------------ operatii

    public Result deposit(Player p, PlayerData d, double raw) {
        if (!canUse(p)) return new Result(false, "Trebuie sa fii langa un ATM.");
        if (!cardOk(p)) return new Result(false, cardMsg(p));
        double a = HypeMath.cleanAmount(raw, max());
        if (a < 0 || a < min()) return new Result(false, "Suma invalida (minim " + HypeMath.round2(min()) + ").");
        UUID u = p.getUniqueId();
        if (!plugin.money().has(u, a)) return new Result(false, "Nu ai destui bani cash.");
        if (!plugin.money().withdraw(u, a)) return new Result(false, "Tranzactie esuata.");
        if (!plugin.bank().deposit(u, a)) {
            plugin.money().deposit(u, a); // rambursare: banca a refuzat
            return new Result(false, "Tranzactie esuata.");
        }
        record(d, u, "DEPOSIT", a, 0, "");
        plugin.service().logTransaction(u, "ATM_DEPOSIT", a, "ATM", where(p));
        return new Result(true, "Ai depus $" + Fmt.money(a) + ".");
    }

    public Result withdraw(Player p, PlayerData d, double raw) {
        if (!canUse(p)) return new Result(false, "Trebuie sa fii langa un ATM.");
        if (!cardOk(p)) return new Result(false, cardMsg(p));
        double a = HypeMath.cleanAmount(raw, max());
        if (a < 0 || a < min()) return new Result(false, "Suma invalida (minim " + HypeMath.round2(min()) + ").");
        rollDay(d);
        double limit = cfg.getDouble("atm.daily-withdraw-limit", 0);
        if (limit > 0 && d.withdrawnToday + a > limit + 1e-9) {
            return new Result(false, "Limita zilnica de retragere: $" + Fmt.money(Math.max(0, limit - d.withdrawnToday)) + " ramasi.");
        }
        UUID u = p.getUniqueId();
        if (!plugin.bank().has(u, a)) return new Result(false, "Nu ai destui bani in banca.");
        if (!plugin.bank().withdraw(u, a)) return new Result(false, "Tranzactie esuata.");
        if (!plugin.money().depositBills(p, a)) {
            plugin.bank().deposit(u, a); // rambursare: economia cash a refuzat
            return new Result(false, "Tranzactie esuata.");
        }
        d.withdrawnToday += a;
        record(d, u, "WITHDRAW", a, 0, "");
        plugin.service().logTransaction(u, "ATM_WITHDRAW", a, "ATM", where(p));
        return new Result(true, "Ai retras $" + Fmt.money(a) + ". Bancnotele sunt in inventar.");
    }

    public Result transfer(Player p, PlayerData d, String toName, double raw, PlayerData receiverData) {
        return transfer(p, d, toName, raw, receiverData, "");
    }

    public Result transfer(Player p, PlayerData d, String toName, double raw, PlayerData receiverData, String note) {
        if (!canUse(p)) return new Result(false, "Trebuie sa fii langa un ATM.");
        if (!cardOk(p)) return new Result(false, cardMsg(p));
        double a = HypeMath.cleanAmount(raw, max());
        if (a < 0 || a < min()) return new Result(false, "Suma invalida (minim " + HypeMath.round2(min()) + ").");
        if (toName == null || toName.trim().isEmpty() || toName.length() > 16) return new Result(false, "Nume de jucator invalid.");
        OfflinePlayer target = Bukkit.getPlayerExact(toName);
        if (target == null) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(toName);
            if (off != null && (off.hasPlayedBefore() || off.isOnline())) target = off;
        }
        if (target == null) return new Result(false, "Jucatorul '" + toName + "' nu exista.");
        UUID u = p.getUniqueId();
        UUID t = target.getUniqueId();
        if (u.equals(t)) return new Result(false, "Nu poti transfera catre tine.");
        rollDay(d);
        double limit = cfg.getDouble("atm.daily-transfer-limit", 0);
        if (limit > 0 && d.transferredToday + a > limit + 1e-9) {
            return new Result(false, "Limita zilnica de transfer: $" + Fmt.money(Math.max(0, limit - d.transferredToday)) + " ramasi.");
        }
        double fee = HypeMath.fee(a, cfg.getDouble("atm.fee-percent", 1.0), cfg.getDouble("atm.fee-min", 0.5));
        double total = HypeMath.round2(a + fee);
        if (!plugin.bank().has(u, total)) return new Result(false, "Ai nevoie de $" + Fmt.money(total) + " in banca (suma + taxa $" + Fmt.money(fee) + ").");
        if (!plugin.bank().withdraw(u, total)) return new Result(false, "Tranzactie esuata.");
        if (!plugin.bank().deposit(t, a)) {
            plugin.bank().deposit(u, total); // rambursare completa
            return new Result(false, "Tranzactie esuata.");
        }
        d.transferredToday += a;
        String tname = target.getName() == null ? toName : target.getName();
        record(d, u, "TRANSFER_OUT", a, fee, tname);
        if (receiverData != null) record(receiverData, t, "TRANSFER_IN", a, 0, p.getName());
        else hdb.addTx(t, "TRANSFER_IN", a, 0, p.getName(), System.currentTimeMillis(), plugin.bank().balance(t));
        plugin.service().logTransaction(u, "ATM_TRANSFER", -a, tname + (cleanNote(note).isEmpty() ? "" : " | " + cleanNote(note)), where(p));
        plugin.service().logTransaction(t, "ATM_TRANSFER_IN", a, p.getName(), where(p));
        String n = cleanNote(note);
        plugin.notifications().send(t, ro.solarstudios.flowcity.bridge.notify.NotifyModule.Type.MONEY, "Transfer primit", "$" + Fmt.money(a) + " de la " + p.getName() + (n.isEmpty() ? "" : " - " + n));
        return new Result(true, "Ai transferat $" + Fmt.money(a) + " catre " + tname + " (taxa $" + Fmt.money(fee) + ").");
    }
}

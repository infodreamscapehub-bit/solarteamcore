package ro.solarstudios.flowcity.bridge.net;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.eadmin.EAdminService;
import ro.solarstudios.flowcity.bridge.eadmin.EAdminService.SType;
import ro.solarstudios.flowcity.bridge.module.Modules;

/**
 * Canalul flowcity:bridge. Modul din client trimite cereri, pluginul valideaza permisiunile
 * (niciodata nu ne bazam pe client) si raspunde cu payload-uri codificate ca PacketBuffer.
 */
public final class BridgeChannel implements PluginMessageListener {
    private final FlowCityBridge plugin;
    private final EAdminService service;
    private final Set<UUID> withMod = ConcurrentHashMap.newKeySet();

    public BridgeChannel(FlowCityBridge plugin, EAdminService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void register() {
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, Op.CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, Op.CHANNEL);
    }

    public void unregister() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, Op.CHANNEL);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, Op.CHANNEL);
    }

    public boolean hasMod(Player p) {
        return withMod.contains(p.getUniqueId());
    }

    public void forget(UUID u) {
        withMod.remove(u);
    }

    // ------------------------------------------------------------------ trimitere

    public void send(Player p, int op, Wire.Out body) {
        if (!p.isOnline()) return;
        Wire.Out out = new Wire.Out().u8(op);
        if (body != null) {
            for (byte b : body.bytes()) out.u8(b);
        }
        p.sendPluginMessage(plugin, Op.CHANNEL, out.bytes());
    }

    /** Trimite un mesaj de modul. Limita pachetului server->client este ~1 MB; pentru date mari trimite pe bucati. */
    public void sendModule(Player p, String moduleId, String action, Wire.Out body) {
        Wire.Out out = new Wire.Out().utf(moduleId).utf(action);
        if (body != null) {
            for (byte b : body.bytes()) out.u8(b);
        }
        byte[] data = out.bytes();
        if (data.length > 900_000) {
            plugin.getLogger().warning("Mesaj modul prea mare (" + data.length + " B): " + moduleId + "/" + action);
            return;
        }
        if (!p.isOnline()) return;
        Wire.Out frame = new Wire.Out().u8(Op.S_MODULE);
        for (byte b : data) frame.u8(b);
        p.sendPluginMessage(plugin, Op.CHANNEL, frame.bytes());
    }

    public void toast(Player p, String message, boolean error) {
        send(p, Op.S_TOAST, new Wire.Out().utf(message).bool(error));
    }

    public void open(Player p) {
        send(p, Op.S_OPEN, new Wire.Out().bool(true).utf(p.getName()).utf(service.serverName()));
    }

    public void sendProfile(Player viewer, UUID target) {
        final boolean ip = viewer.hasPermission("flowcity.eadmin.ip");
        service.profile(target, ip).whenComplete((out, err) -> sync(() -> {
            if (err != null || out == null) toast(viewer, "Nu am putut incarca profilul.", true);
            else send(viewer, Op.S_PROFILE, out);
        }));
    }

    private void sync(Runnable r) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    // ------------------------------------------------------------------ primire

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Op.CHANNEL.equals(channel) || message.length == 0) return;
        // Se ruleaza pe thread-ul principal in Bukkit/Spigot, dar ne asiguram.
        if (!Bukkit.isPrimaryThread()) {
            final byte[] copy = message.clone();
            sync(() -> onPluginMessageReceived(channel, player, copy));
            return;
        }
        try {
            Wire.In in = new Wire.In(message);
            int op = in.u8();
            if (op == Op.C_HELLO) {
                int proto = in.remaining() > 0 ? in.varInt() : 0;
                if (proto == Op.PROTOCOL) {
                    withMod.add(player.getUniqueId());
                    Modules.ready(player);
                } else player.sendMessage("§eModul FlowCityCore are alta versiune de protocol; actualizeaza-l.");
                return;
            }
            if (op == Op.C_MODULE) {
                if (!hasMod(player)) return; // fara HELLO valid nu acceptam mesaje de module
                String moduleId = in.utf();
                String action = in.utf();
                Modules.dispatch(player, moduleId, action, in);
                return;
            }
            if (!player.hasPermission("flowcity.eadmin")) {
                if (op == Op.C_OPEN_REQUEST) toast(player, "Nu ai acces la eAdmin.", true);
                return; // ignoram tacit orice alt pachet fara permisiune
            }
            handle(player, op, in);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Pachet invalid de la " + player.getName() + ": " + ex);
        }
    }

    private void handle(Player staff, int op, Wire.In in) {
        switch (op) {
            case Op.C_OPEN_REQUEST:
                open(staff);
                break;
            case Op.C_PLAYER_LIST: {
                String q = in.utf();
                int page = in.i32();
                service.playerList(q, page).whenComplete((out, err) -> sync(() -> {
                    if (out != null) send(staff, Op.S_PLAYER_LIST, out);
                }));
                break;
            }
            case Op.C_PROFILE:
                sendProfile(staff, parseUuid(in.utf()));
                break;
            case Op.C_LOGS: {
                UUID u = parseUuid(in.utf());
                String type = in.utf();
                int page = in.i32();
                if (!EAdminService.isLogType(type)) return;
                service.logs(u, type, page).whenComplete((out, err) -> sync(() -> {
                    if (out != null) send(staff, Op.S_LOGS, out);
                }));
                break;
            }
            case Op.C_SANCTION: {
                if (!need(staff, "flowcity.eadmin.sanction")) return;
                UUID u = parseUuid(in.utf());
                String type = in.utf();
                int minutes = in.i32();
                String reason = in.utf();
                SType st;
                try {
                    st = SType.valueOf(type);
                } catch (IllegalArgumentException e) {
                    return;
                }
                if (u.equals(staff.getUniqueId())) {
                    toast(staff, "Nu te poti sanctiona singur.", true);
                    return;
                }
                Player online = Bukkit.getPlayer(u);
                if (online != null && online.hasPermission("flowcity.eadmin.immune") && !staff.isOp()) {
                    toast(staff, "Jucatorul este imun la sanctiuni.", true);
                    return;
                }
                service.nameOf(u).whenComplete((name, err) -> sync(() ->
                    service.applySanction(staff.getName(), u, name == null ? u.toString() : name, st, minutes, reason, (ok, msg) -> {
                        toast(staff, msg, !ok);
                        if (ok) sendProfile(staff, u);
                    })));
                break;
            }
            case Op.C_REVOKE: {
                if (!need(staff, "flowcity.eadmin.sanction")) return;
                int id = in.i32();
                service.revoke(id, staff.getName(), u -> {
                    if (u == null) {
                        toast(staff, "Sanctiunea nu a fost gasita.", true);
                    } else {
                        toast(staff, "Sanctiune revocata.", false);
                        sendProfile(staff, u);
                    }
                });
                break;
            }
            case Op.C_NOTE_ADD: {
                if (!need(staff, "flowcity.eadmin.notes")) return;
                UUID u = parseUuid(in.utf());
                String text = in.utf();
                if (text.trim().isEmpty()) return;
                service.addNote(u, staff.getName(), text, () -> {
                    toast(staff, "Nota adaugata.", false);
                    sendProfile(staff, u);
                });
                break;
            }
            case Op.C_NOTE_DEL: {
                if (!need(staff, "flowcity.eadmin.notes")) return;
                int id = in.i32();
                service.deleteNote(id, u -> {
                    if (u == null) {
                        toast(staff, "Nota nu exista.", true);
                    } else {
                        toast(staff, "Nota stearsa.", false);
                        sendProfile(staff, u);
                    }
                });
                break;
            }
            case Op.C_ACTION: {
                if (!need(staff, "flowcity.eadmin.tp")) return;
                String action = in.utf();
                UUID u = parseUuid(in.utf());
                Player target = Bukkit.getPlayer(u);
                if (target == null) {
                    toast(staff, "Jucatorul nu este online.", true);
                    return;
                }
                if ("TP_TO".equals(action)) {
                    staff.teleport(target.getLocation());
                    toast(staff, "Teleportat la " + target.getName() + ".", false);
                } else if ("TP_HERE".equals(action)) {
                    target.teleport(staff.getLocation());
                    toast(staff, target.getName() + " a fost adus la tine.", false);
                }
                break;
            }
            default:
                break;
        }
    }

    private boolean need(Player p, String perm) {
        if (p.hasPermission(perm)) return true;
        toast(p, "Nu ai permisiunea " + perm, true);
        return false;
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("uuid invalid");
        }
    }
}

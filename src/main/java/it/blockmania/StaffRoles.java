package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gradi staff assegnati dal profilo (pexa). Si salvano in roles.yml; al player (online e al join) si danno i permessi
 * blockmania.role.<id> di tutti i gradi fino a quello scelto con un PermissionAttachment, cosi' AdminCommand li vede
 * come se arrivassero da LuckPerms. Gli id qui usano "_" (jr_helper), nel config e nei permessi "-" (jr-helper).
 */
public final class StaffRoles implements Listener {

    /** Scala dei livelli (come adminmania.roles): ogni grado include quelli sotto. */
    public static final List<String> SCALE = List.of("jr_helper", "helper", "jr_mod", "mod", "sr_mod", "jr_admin", "admin", "sr_admin");
    /** Ruoli speciali fuori scala: permesso a parte, si sommano al grado staff. */
    public static final List<String> SPECIAL = List.of("builder", "dev", "t_maker");
    /** Ordine mostrato nel popup (indice = glifo U+E120 + indice, ruolo-1..11). */
    public static final List<String> ORDER = List.of("jr_helper", "helper", "jr_mod", "mod", "sr_mod", "jr_admin", "admin", "sr_admin",
            "builder", "dev", "t_maker");

    public static boolean isSpecial(String roleId) {
        return SPECIAL.contains(roleId);
    }

    private final BlockMania plugin;
    private final File file;
    private final Map<UUID, String> roles = new HashMap<>();
    private final Map<UUID, java.util.Set<String>> specials = new HashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public StaffRoles(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "roles.yml");
    }

    /** Id del permesso/config di un grado: jr_helper -> jr-helper. */
    public static String permId(String roleId) {
        return roleId.replace('_', '-');
    }

    /** Nome leggibile dal config (adminmania.roles.<id>), altrimenti l'id. */
    public String label(String roleId) {
        return plugin.getConfig().getString("adminmania.roles." + permId(roleId), roleId);
    }

    public void load() {
        roles.clear();
        specials.clear();
        if (file.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            var sec = y.getConfigurationSection("roles");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    try {
                        String r = sec.getString(key);
                        if (r != null && SCALE.contains(r)) {
                            roles.put(UUID.fromString(key), r);
                        }
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("roles.yml: uuid non valido " + key);
                    }
                }
            }
            var sp = y.getConfigurationSection("special");
            if (sp != null) {
                for (String key : sp.getKeys(false)) {
                    try {
                        java.util.Set<String> set = new java.util.LinkedHashSet<>();
                        for (String r : sp.getStringList(key)) {
                            if (SPECIAL.contains(r)) {
                                set.add(r);
                            }
                        }
                        if (!set.isEmpty()) {
                            specials.put(UUID.fromString(key), set);
                        }
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("roles.yml: uuid non valido " + key);
                    }
                }
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            apply(p);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : roles.entrySet()) {
            y.set("roles." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, java.util.Set<String>> e : specials.entrySet()) {
            y.set("special." + e.getKey(), new java.util.ArrayList<>(e.getValue()));
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("roles.yml: " + ex.getMessage());
        }
    }

    /** Grado salvato del player (id di ORDER) o null se nessuno. */
    public String roleOf(UUID id) {
        return id == null ? null : roles.get(id);
    }

    /** Ruoli speciali (builder, dev, t_maker) del player. */
    public java.util.Set<String> specialsOf(UUID id) {
        java.util.Set<String> s = id == null ? null : specials.get(id);
        return s == null ? java.util.Set.of() : java.util.Set.copyOf(s);
    }

    /**
     * Assegna il grado. Un grado della scala (null o id sconosciuto = lo toglie) sostituisce il precedente e non
     * tocca i ruoli speciali; un ruolo speciale si aggiunge (o si toglie se c'e' gia') senza toccare il grado staff.
     */
    public void set(UUID id, String roleId, CommandSender by) {
        if (id == null) {
            return;
        }
        String old = roles.get(id);
        if (isSpecial(roleId)) {
            java.util.Set<String> set = specials.computeIfAbsent(id, k -> new java.util.LinkedHashSet<>());
            if (!set.remove(roleId)) {
                set.add(roleId);
            }
            if (set.isEmpty()) {
                specials.remove(id);
            }
            old = "speciale " + roleId;
        } else if (roleId == null || !SCALE.contains(roleId)) {
            roles.remove(id);
        } else {
            roles.put(id, roleId);
        }
        save();
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            apply(p);
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        plugin.getLogger().info("Grado di " + (name == null ? id : name) + ": " + old + " -> " + roles.get(id) + " " + specials.getOrDefault(id, java.util.Set.of())
                + (by == null ? "" : " (da " + by.getName() + ")"));
    }

    /** Toglie i permessi precedenti e da' quelli dei gradi fino a quello salvato. */
    public void apply(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) {
            try {
                p.removeAttachment(old);
            } catch (IllegalArgumentException ex) {
                // gia' rimosso
            }
        }
        String role = roles.get(p.getUniqueId());
        java.util.Set<String> sp = specials.get(p.getUniqueId());
        if (role == null && (sp == null || sp.isEmpty())) {
            return;
        }
        PermissionAttachment att = p.addAttachment(plugin);
        int upTo = role == null ? -1 : SCALE.indexOf(role);
        for (int i = 0; i <= upTo; i++) {
            att.setPermission("blockmania.role." + permId(SCALE.get(i)), true);
        }
        if (sp != null) {
            for (String r : sp) {
                att.setPermission("blockmania.role." + permId(r), true);
            }
        }
        attachments.put(p.getUniqueId(), att);
        p.recalculatePermissions();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        attachments.remove(event.getPlayer().getUniqueId());
    }
}

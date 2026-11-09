package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Popup "cambia grado" (glifo U+E11A, font userprofile3) sopra il profilo scurito. La barra del grado e'
 * un'immagine 256x256 (U+E120 + indice, font ruoli) disegnata nello stesso modo del popup. Parte da jr_helper;
 * le frecce cambiano grado, CONFERMA assegna con StaffRoles.set, ANNULLA torna indietro.
 * Slot (container = immagine - (7,15)): riga 1 = frecce 10-11 / barra 12-14 / freccia 15-16; riga 3 = CONFERMA 28-30, ANNULLA 32-34.
 */
public final class RoleGui implements Listener {

    private static final String FONT_POPUP = "minecraft:userprofile3";
    private static final String FONT_ROLES = "minecraft:ruoli";
    private static final char POPUP = '';
    private static final char ROLE_BASE = '';
    private static final int IMG = -15;
    /**
     * Avanzamenti DICHIARATI (stesso schema di ProfileLists: immagine a -15, advance = reale - 7).
     * Reale di E11A (userprofile-27.png): ultima colonna opaca 167 -> 169, dichiarato 162.
     * Reale delle barre ruolo-1..11: ultima colonna opaca 126 -> 128, dichiarato 121 (tutte uguali).
     */
    private static final int ADV = 162;
    private static final int ADV_ROLE = 121;
    /** Correzione misurata in gioco: la barra usciva 5 px immagine a destra del centro tra le frecce. */
    private static final int ROLE_SHIFT = -7;
    private static final int SIZE = 54;

    private static final int[] SLOTS_PREV = {10, 11};
    private static final int[] SLOTS_BAR = {12, 13, 14};
    private static final int[] SLOTS_NEXT = {15, 16};
    private static final int[] SLOTS_YES = {28, 29, 30};
    private static final int[] SLOTS_NO = {32, 33, 34};

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        Session session;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Session {
        Player staff;
        UUID target;
        int index;
        Consumer<GuiTitle> background;
        Runnable onBack;
        boolean done;
        Holder holder;
    }

    private final BlockMania plugin;
    private final Set<UUID> switching = new HashSet<>();
    private final java.util.Map<UUID, Session> sessions = new java.util.HashMap<>();

    public RoleGui(BlockMania plugin) {
        this.plugin = plugin;
    }

    public boolean owns(Player p) {
        return p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder;
    }

    /** Apre il popup per cambiare il grado di target. Parte dal primo grado (jr_helper). */
    public void open(Player staff, UUID target, Consumer<GuiTitle> background, Runnable onBack) {
        Session s = new Session();
        s.staff = staff;
        s.target = target;
        s.index = 0;
        s.background = background;
        s.onBack = onBack;
        show(s);
    }

    private GuiTitle title(Session s) {
        GuiTitle g = new GuiTitle(plugin);
        if (s.background != null) {
            UserProfilePowers.drawDimmed(plugin, g, s.background);
        }
        g.at(IMG, "&f" + POPUP, FONT_POPUP, ADV);
        g.at(IMG + ROLE_SHIFT, "&f" + (char) (ROLE_BASE + s.index), FONT_ROLES, ADV_ROLE);
        return g;
    }

    private void show(Session s) {
        Holder h = new Holder();
        h.session = s;
        s.holder = h;
        h.inventory = Bukkit.createInventory(h, SIZE, title(s).build());
        String id = StaffRoles.ORDER.get(s.index);
        StaffRoles roles = plugin.getStaffRoles();
        String current = roles == null ? null : roles.roleOf(s.target);
        String name = roles == null ? id : roles.label(id);
        boolean special = StaffRoles.isSpecial(id);
        boolean has = special && roles != null && roles.specialsOf(s.target).contains(id);
        List<String> barLore = List.of("&7Ruolo scelto: &f" + name + (special ? " &8(speciale)" : ""),
                "&7Grado staff attuale: &f" + (current == null ? "nessuno" : roles.label(current)), "", "&eusa le frecce per cambiare");
        for (int i : SLOTS_PREV) {
            h.inventory.setItem(i, pane("&7grado precedente", List.of("&eclicca per scorrere")));
        }
        for (int i : SLOTS_NEXT) {
            h.inventory.setItem(i, pane("&7grado successivo", List.of("&eclicca per scorrere")));
        }
        for (int i : SLOTS_BAR) {
            h.inventory.setItem(i, pane("&f" + name, barLore));
        }
        for (int i : SLOTS_YES) {
            h.inventory.setItem(i, pane("&aconferma", List.of("&7Assegna il grado &f" + name + "&7 a &f" + UserProfilePowers.nameOf(s.target) + "&7.",
                    special ? (has ? "&7Ce l'ha gia': confermando lo toglie. Il grado staff non cambia."
                            : "&7Ruolo speciale: si aggiunge, il grado staff non cambia.")
                            : "&7Riceve anche i gradi sotto di questo.", "", "&eclicca per confermare")));
        }
        for (int i : SLOTS_NO) {
            h.inventory.setItem(i, pane("&cannulla", List.of("&7Torna indietro senza fare niente.", "", "&eclicca per annullare")));
        }
        UUID uid = s.staff.getUniqueId();
        switching.add(uid);
        try {
            s.staff.openInventory(h.inventory);
        } finally {
            switching.remove(uid);
        }
        sessions.put(uid, s);
    }

    private boolean in(int[] arr, int slot) {
        for (int a : arr) {
            if (a == slot) {
                return true;
            }
        }
        return false;
    }

    /** Grado della scala: solo chi ha un grado piu' alto (o e' OP/console). Ruoli speciali: da admin in su. */
    private boolean canAssign(CommandSender who, int index) {
        if (who.isOp()) {
            return true;
        }
        AdminCommand a = plugin.getAdminCommand();
        if (a == null) {
            return false;
        }
        // i ruoli speciali (fuori scala) li assegna chi e' almeno admin
        int need = index < StaffRoles.SCALE.size() ? index + 2 : StaffRoles.SCALE.indexOf("admin") + 1;
        return a.level(who) >= need;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        Session s = h.session;
        int slot = event.getRawSlot();
        int n = StaffRoles.ORDER.size();
        if (in(SLOTS_PREV, slot)) {
            s.index = (s.index + n - 1) % n;
            Bukkit.getScheduler().runTask(plugin, () -> show(s));
        } else if (in(SLOTS_NEXT, slot)) {
            s.index = (s.index + 1) % n;
            Bukkit.getScheduler().runTask(plugin, () -> show(s));
        } else if (in(SLOTS_YES, slot)) {
            if (!canAssign(p, s.index)) {
                GuiMsg.send(plugin, p, "&cpuoi assegnare solo gradi piu' bassi del tuo.");
                plugin.playConfiguredSound(p.getLocation(), "message-sounds.error");
                return;
            }
            String id = StaffRoles.ORDER.get(s.index);
            s.done = true;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getStaffRoles().set(s.target, id, p);
                GuiMsg.send(plugin, p, "&7grado &f" + plugin.getStaffRoles().label(id) + "&7 assegnato a &f"
                        + UserProfilePowers.nameOf(s.target) + "&7.");
                back(s);
            });
        } else if (in(SLOTS_NO, slot)) {
            s.done = true;
            Bukkit.getScheduler().runTask(plugin, () -> back(s));
        }
    }

    private void back(Session s) {
        sessions.remove(s.staff.getUniqueId());
        if (s.onBack != null) {
            s.onBack.run();
        } else {
            s.staff.closeInventory();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder h && event.getPlayer() instanceof Player p
                && !switching.contains(p.getUniqueId()) && sessions.get(p.getUniqueId()) == h.session) {
            sessions.remove(p.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        switching.remove(event.getPlayer().getUniqueId());
    }

    private ItemStack pane(String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        meta.displayName(plugin.text(name));
        List<net.kyori.adventure.text.Component> out = new ArrayList<>();
        for (String raw : lore) {
            out.add(raw == null || raw.isBlank() ? plugin.color(" ") : plugin.text(raw));
        }
        meta.lore(out);
        stack.setItemMeta(meta);
        return stack;
    }
}

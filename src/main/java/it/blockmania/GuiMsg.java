package it.blockmania;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * Messaggi dopo azioni nelle GUI: vanno in chat come sempre e, se il giocatore ha una GUI aperta
 * (dove la chat si legge male), anche nella bossbar in alto (la stessa del messaggio "HAI RICEVUTO ...").
 * Il testo usa i codici colore & del plugin; &c = errore (icona rossa), &a = successo (icona verde),
 * &e = avviso, tutto il resto = info.
 */
public final class GuiMsg {

    /** Caratteri visibili massimi nella bossbar (il resto viene tagliato con ...). */
    private static final int MAX_VISIBLE = 64;

    private GuiMsg() {
    }

    /** Chat sempre; bossbar in piu' se il giocatore ha una GUI aperta. */
    public static void send(BlockMania plugin, Player p, String msg) {
        if (p == null || msg == null) {
            return;
        }
        p.sendMessage(plugin.color(msg));
        boss(plugin, p, msg);
    }

    /** Come send ma per un CommandSender qualunque (la console riceve solo la chat). */
    public static void send(BlockMania plugin, CommandSender to, String msg) {
        if (to instanceof Player p) {
            send(plugin, p, msg);
        } else if (to != null && msg != null) {
            to.sendMessage(plugin.color(msg));
        }
    }

    /** Come send, ma in chat con il tiny text del plugin (plugin.text), come i messaggi dei comandi. */
    public static void sendTiny(BlockMania plugin, CommandSender to, String msg) {
        if (to == null || msg == null) {
            return;
        }
        to.sendMessage(plugin.text(msg));
        if (to instanceof Player p) {
            boss(plugin, p, msg);
        }
    }

    /** Solo bossbar (se la GUI e' aperta), senza chat. */
    public static void boss(BlockMania plugin, Player p, String msg) {
        if (p == null || msg == null) {
            return;
        }
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            // dalla chat asincrona: la bossbar si tocca solo dal thread principale
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> boss(plugin, p, msg));
            return;
        }
        if (!plugin.hasOpenGui(p)) {
            return;
        }
        String type = typeOf(msg);
        FileConfiguration c = plugin.getConfig();
        String base = "styles." + type;
        String baseColor = c.getString(base + ".color", "&7");
        String icon = c.getString(base + ".icon", "");
        if (icon == null || icon.equalsIgnoreCase("none")) {
            icon = "";
        } else if (!icon.isBlank()) {
            icon = icon + " ";
        }
        // il testo senza codici: il colore lo da' lo stile (come i messaggi standard)
        String plain = BlockMania.stripCodes(msg);
        plain = plain.replaceAll("\\s+", " ").trim();
        if (plain.length() > MAX_VISIBLE) {
            plain = plain.substring(0, MAX_VISIBLE - 3) + "...";
        }
        String legacy = baseColor + plugin.tiny(plugin.keyMarks(icon + plain));
        plugin.showBossMessage(p, legacy);
    }

    private static String typeOf(String msg) {
        String t = msg.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("&c") || t.startsWith("&4")) {
            return "error";
        }
        if (t.startsWith("&a") || t.startsWith("&2")) {
            return "success";
        }
        if (t.startsWith("&e") || t.startsWith("&6")) {
            return "warning";
        }
        return "info";
    }
}

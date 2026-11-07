package it.blockmania;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialog 1.21 (Paper Dialog API, sperimentale) per aprire i link dei ticket.
 *
 * Contratto usato da Tickets.java:
 *   TicketDialog.open(player, List.of(new TicketDialog.Link("Discord", "https://...")), () -> riapri la GUI precedente);
 *
 * Look "clicklink": si vede SOLO la grafica della pack (font minecraft:clicklink: U+E000 staff,
 * U+E001 player = GUI ticket intera con il popup grigio; sono immagini 256x256 con ascent 73).
 * Il dialog non puo' non avere pulsanti vanilla, quindi:
 *  - titolo vuoto, nessun testo vanilla, un solo plainMessage (larghezza 200);
 *  - il glifo sta sulla riga "0" del body; le righe sotto (9 px l'una) sono fatte di soli
 *    glifi-spazio invisibili (U+3410..U+3415, positivi, nel default.json della pack) con
 *    ClickEvent: cosi' la zona di click combacia con i pulsanti DISEGNATI (rosso = annulla,
 *    verde = apri il primo link) e nella zona grigia ci sono i link (testo cliccabile);
 *  - il click event openUrl nel testo del body e' supportato dal client (mostra la sua
 *    conferma "apri link"); annulla usa ClickEvent.callback (custom click, gestito da Paper);
 *  - pulsante vanilla OBBLIGATORIO (tipo notice = 1 pulsante nel footer): larghezza 1, label
 *    vuota, senza tooltip; e' anche l'exit action quindi ESC = annulla (stessa callback);
 *  - pause(false) + afterAction NONE (obbligatorio insieme): il dialog resta aperto dopo un click.
 *
 * Allineamento: ascent 73 in font/clicklink.json (le righe da 9 px cadono sui pulsanti: rosso =
 * righe 1-2, grigio = 3-5, verde = 6-8 dopo la riga del glifo). Se cambi l'ascent cambia tutto.
 */
public final class TicketDialog {

    public record Link(String label, String url) {}

    /** Font della pack (font/clicklink.json) e glifi: GUI ticket intera con popup, staff / player. */
    private static final Key FONT = Key.key("minecraft", "clicklink");
    private static final String GLYPH_STAFF = "\ue000";
    private static final String GLYPH_PLAYER = "\ue001";

    /** Glifi-spazio (positivi) del default.json della pack: 1, 2, 4, 8, 16, 32 px. */
    private static final String[] SP = {"\u3410", "\u3411", "\u3412", "\u3413", "\u3414", "\u3415"};

    /**
     * 7 sopra e 12 sotto (prima 5 e 10): l immagine sporge 61 px sopra la riga del glifo e il body (scroll) la tagliava in alto;
     * +2 righe sopra e +2 sotto = pannello sempre nella stessa posizione sullo schermo ma intero.
     * Righe vuote sopra/sotto la riga del glifo: l'immagine e' alta 230 px (righe 5..235 del png,
     * ascent 73) e la riga del glifo e' la numero 7; 5 sopra (calibrate in gioco: il body parte dall'alto, 7 era 17 px troppo in basso;
     * -2 righe = -18 px, +1 px compensato dall'ascent 73) + 9 zone cliccabili + 10 sotto.
     */
    private static final int LINES_ABOVE = 7;
    private static final int LINES_BELOW = 14;
    // Allineamento verticale: il dialog e' centrato, quindi la riga del glifo sta a c + 13.5*(sopra-sotto) px schermo (scala 3).
    // Con 7/12 il pannello stava ~22 px (7 px GUI) PIU' IN BASSO della GUI ticket dietro; con 7/14 sale di ~27 px (resta ~5 px alto:
    // se serve il ritocco fine, abbassare di 1-2 l'ascent 73 di font/clicklink.json, i pulsanti cliccabili restano nella zona giusta).
    /**
     * Larghezza del body: 700 px (oltre lo schermo GUI a scala 3, ~640) cosi' i bordi bianchi di focus del widget testo
     * (le "bande" laterali, prima a +-100 px dal centro) escono dallo schermo. Le righe sono CENTRATE nel body (il link
     * scritto e' al centro dello schermo anche con body 200), quindi il pannello resta dov'e' senza spazi iniziali.
     * Se in gioco il pannello risultasse spostato: aggiungere sp((BODY_WIDTH-200)/2) all'inizio di ogni riga.
     */
    private static final int BODY_WIDTH = 1024;   // massimo ammesso: i bordi di focus escono dallo schermo anche con GUI scale bassa
    /** Link massimi mostrati nella zona grigia (3 righe). */
    private static final int MAX_LINKS = 3;

    private TicketDialog() {}

    public static void open(Player p, List<Link> links, Runnable onCancel) {
        open(p, links, onCancel, false);
    }

    /** staff = true usa la grafica STAFF, altrimenti PLAYER (lucchetto e stelle). */
    public static void open(Player p, List<Link> links, Runnable onCancel, boolean staff) {
        if (p == null || !p.isOnline()) return;
        JavaPlugin pl = JavaPlugin.getProvidingPlugin(TicketDialog.class);
        try {
            openDialog(p, links, onCancel, staff);
        } catch (Throwable ex) {
            pl.getLogger().warning("[TicketDialog] Dialog non mostrato a " + p.getName() + ": " + ex);
            ex.printStackTrace();
            fallbackChat(p, links);
        }
    }

    /** Se il Dialog fallisce: link cliccabili in chat. */
    private static void fallbackChat(Player p, List<Link> links) {
        if (!p.isOnline()) return;
        p.sendMessage(Component.text("Non riesco ad aprire la finestra, clicca il link qui sotto:", NamedTextColor.YELLOW));
        if (links == null) return;
        for (Link l : links) {
            if (l == null || l.url() == null) continue;
            String u = l.url().trim();
            if (!(u.startsWith("http://") || u.startsWith("https://"))) continue;
            p.sendMessage(Component.text("» " + u, NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(u)));
        }
    }

    /** Stringa di glifi-spazio larga esattamente n px (n >= 0). */
    private static String sp(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = SP.length - 1; i >= 0; i--) {
            int w = 1 << i;
            while (n >= w) {
                sb.append(SP[i]);
                n -= w;
            }
        }
        return sb.toString();
    }

    /** Riga senza inchiostro (non vuota, cosi' il client non la scarta). */
    private static Component blank() {
        return Component.text(SP[1]);
    }

    private static boolean validUrl(String u) {
        return u != null && (u.startsWith("http://") || u.startsWith("https://"));
    }

    private static ClickEvent cancelEvent(Runnable onCancel) {
        return ClickEvent.callback(
                aud -> {
                    aud.closeDialog();
                    run(onCancel);
                },
                ClickCallback.Options.builder().build()); // 1 uso, durata di default (12h)
    }

    private static void openDialog(Player p, List<Link> links, Runnable onCancel, boolean staff) {
        List<Link> ok = new ArrayList<>();
        if (links != null) {
            for (Link l : links) {
                if (l == null || l.url() == null) continue;
                String u = l.url().trim();
                if (!validUrl(u)) continue;
                String label = (l.label() == null || l.label().isBlank()) ? u : l.label().trim();
                if (label.length() > 28) label = label.substring(0, 27) + "...";
                ok.add(new Link(label, u));
                if (ok.size() >= MAX_LINKS) break;
            }
        }
        // senza link non c'e' nulla da mostrare: si torna subito indietro.
        if (ok.isEmpty()) {
            run(onCancel);
            return;
        }

        ClickEvent cancel = cancelEvent(onCancel);
        ClickEvent openFirst = ClickEvent.openUrl(ok.get(0).url());

        // zona grigia: 3 righe (indici 3,4,5 rispetto alla riga del glifo)
        Component[] gray = {blank(), blank(), blank()};
        int n = ok.size();
        int[] slots = n == 1 ? new int[] {1} : n == 2 ? new int[] {0, 2} : new int[] {0, 1, 2};
        for (int i = 0; i < n; i++) {
            Link l = ok.get(i);
            gray[slots[i]] = Component.text(l.label(), NamedTextColor.DARK_BLUE)
                    .decorate(TextDecoration.UNDERLINED)
                    .decoration(TextDecoration.ITALIC, false)
                    .shadowColor(ShadowColor.none())
                    .clickEvent(ClickEvent.openUrl(l.url()))
                    .hoverEvent(HoverEvent.showText(Component.text(l.url(), NamedTextColor.GRAY)));
        }

        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < LINES_ABOVE; i++) lines.add(blank());
        // riga 0: grafica. Il glifo ha il font clicklink; lo spazio finale e' un FRATELLO (non figlio)
        // con font default esplicito, altrimenti erediterebbe clicklink e uscirebbe come tofu.
        lines.add(Component.empty()
                .append(Component.text(staff ? GLYPH_STAFF : GLYPH_PLAYER, NamedTextColor.WHITE)
                        .font(FONT)
                        .shadowColor(ShadowColor.none()))
                .append(Component.text(sp(4)).font(Key.key("minecraft", "default"))));  // 186 + 4 = 190 -> pannello al centro
        Component redLine = Component.text(sp(56))                             // righe 1-2: pulsante rosso
                .clickEvent(cancel)
                .hoverEvent(HoverEvent.showText(Component.text("Annulla", NamedTextColor.RED)));
        lines.add(redLine);
        lines.add(redLine);
        for (Component g : gray) lines.add(g);                                 // righe 3-5: zona grigia
        Component greenLine = Component.text(sp(100))                          // righe 6-8: pulsante verde
                .clickEvent(openFirst)
                .hoverEvent(HoverEvent.showText(Component.text(ok.get(0).url(), NamedTextColor.GRAY)));
        lines.add(greenLine);
        lines.add(greenLine);
        lines.add(greenLine);
        for (int i = 0; i < LINES_BELOW; i++) lines.add(blank());

        net.kyori.adventure.text.TextComponent.Builder body = Component.text();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) body.append(Component.newline());
            body.append(lines.get(i));
        }

        // pulsante vanilla minimo (footer): invisibile quasi, ed e' l'exit action: ESC = annulla
        ActionButton exit = ActionButton.create(
                Component.empty(),
                null,
                1,
                DialogAction.customClick(
                        (view, audience) -> {
                            audience.closeDialog();
                            run(onCancel);
                        },
                        ClickCallback.Options.builder().build()));

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.empty())
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(List.of(DialogBody.plainMessage(body.build(), BODY_WIDTH)))
                        .build())
                .type(DialogType.notice(exit)));

        p.showDialog(dialog);
        JavaPlugin.getProvidingPlugin(TicketDialog.class).getLogger()
                .info("[TicketDialog] Dialog mostrato a " + p.getName() + " (" + ok.size() + " link)");
    }

    /** Esegue sul thread principale (il callback del client potrebbe non esserci). */
    private static void run(Runnable r) {
        if (r == null) return;
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(JavaPlugin.getProvidingPlugin(TicketDialog.class), r);
        }
    }
}

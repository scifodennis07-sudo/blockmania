package it.blockmania;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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
 * Note sull'API (verificate sul javadoc 1.21.7, NON compilate in locale):
 *  - Dialog.create(b -> b.empty().base(...).type(...)) + player.showDialog(dialog)
 *  - DialogType.multiAction(List<ActionButton> actions, ActionButton exitAction, int columns)
 *  - ActionButton.create(label, tooltip, width, DialogAction)
 *  - DialogAction.staticAction(ClickEvent.openUrl(url)): il client mostra da solo
 *    la conferma "apri link" prima di uscire dal gioco.
 *  - DialogAction.customClick(DialogActionCallback, ClickCallback.Options) per "annulla".
 *
 * Scelte per il look "clicklink":
 *  - afterAction NONE: cliccando un link il dialog resta aperto (dopo la conferma
 *    del client si torna qui). Solo "annulla" lo chiude con audience.closeDialog().
 *    Se in 1.21.8 il nome dell'enum fosse diverso, sostituire con
 *    DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE.
 *  - ESC disabilitato (canCloseWithEscape(false)): il giocatore deve scegliere.
 *  - Il glifo 㸊 (immagine "clicklink", lo aggiunge la pack) e' nel titolo; metti
 *    USE_GLYPH=false se la pack non lo contiene ancora (si vedrebbe un quadratino).
 */
public final class TicketDialog {

    public record Link(String label, String url) {}

    /** Glifo della pack (㸊) nel titolo del dialog. */
    private static final boolean USE_GLYPH = true;
    private static final String GLYPH = "㸊";

    private TicketDialog() {}

    public static void open(Player p, List<Link> links, Runnable onCancel) {
        if (p == null || !p.isOnline()) return;
        JavaPlugin pl = JavaPlugin.getProvidingPlugin(TicketDialog.class);
        try {
            openDialog(p, links, onCancel);
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

    private static void openDialog(Player p, List<Link> links, Runnable onCancel) {
        List<ActionButton> buttons = new ArrayList<>();
        if (links != null) {
            for (Link l : links) {
                if (l == null || l.url() == null) continue;
                String u = l.url().trim();
                if (!(u.startsWith("http://") || u.startsWith("https://"))) continue;
                String label = (l.label() == null || l.label().isBlank()) ? u : l.label();
                buttons.add(ActionButton.create(
                        Component.text(label, NamedTextColor.GREEN)
                                .decoration(TextDecoration.ITALIC, false),
                        Component.text(u, NamedTextColor.GRAY),
                        200,
                        DialogAction.staticAction(ClickEvent.openUrl(u))));
            }
        }
        // multiAction vuole almeno un bottone: senza link si torna subito indietro.
        if (buttons.isEmpty()) {
            run(onCancel);
            return;
        }

        ActionButton cancel = ActionButton.create(
                Component.text("annulla", NamedTextColor.RED),
                Component.text("Torna indietro", NamedTextColor.GRAY),
                200,
                DialogAction.customClick(
                        (view, audience) -> {
                            audience.closeDialog();
                            run(onCancel);
                        },
                        ClickCallback.Options.builder().build())); // 1 uso, durata di default (12h)

        Component title = USE_GLYPH
                ? Component.text(GLYPH).append(Component.text(" Link", NamedTextColor.WHITE))
                : Component.text("Link", NamedTextColor.WHITE);

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(List.of(DialogBody.plainMessage(
                                Component.text("Scegli un link da aprire.", NamedTextColor.GRAY))))
                        .build())
                .type(DialogType.multiAction(buttons, cancel, 1)));

        p.showDialog(dialog);
        JavaPlugin.getProvidingPlugin(TicketDialog.class).getLogger()
                .info("[TicketDialog] Dialog mostrato a " + p.getName() + " (" + buttons.size() + " link)");
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

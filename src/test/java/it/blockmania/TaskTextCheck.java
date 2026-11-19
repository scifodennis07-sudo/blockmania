package it.blockmania;

import java.util.List;

/**
 * Prova a mano del parser delle task (senza Bukkit e senza JUnit):
 * javac -d out src/main/java/it/blockmania/TaskText.java src/test/java/it/blockmania/TaskTextCheck.java
 * java -cp out it.blockmania.TaskTextCheck
 * Stampa ogni frase con le azioni capite ed esce con codice 1 se un'attesa non torna.
 */
public final class TaskTextCheck {

    private static final List<String> PLAYERS = List.of("DoppiaX", "crack", "Marco_99", "LucaGamer", "Lucas", "Giulia", "zcrask");

    private static int fails;

    public static void main(String[] args) {
        TaskText t = new TaskText(TaskText.defaults());
        // esempi dell'utente
        expect(t, "dare 20 palle standard a DoppiaX togliendole a crack",
                "give 20 ball:standardball -> DoppiaX", "remove 20 ball:standardball -> crack");
        expect(t, "dvar3 a doppiaX 20 standerdepall", "give 20 ball:standardball -> DoppiaX");
        // altri
        expect(t, "togli 500 monete a crack", "remove 500 coin -> crack");
        expect(t, "regala venticinque palle fantasma a Giulia e le stesse a Marco_99",
                "give 25 ball:ghostball -> Giulia", "give 25 ball:ghostball -> Marco_99");
        expect(t, "warna crack 1.2", "warn regola 1.2 -> crack");
        expect(t, "banna crack", "ban -> crack [INCERTO rule: ]");
        expect(t, "setta la stamina di crack a 50", "set 50 stamina -> crack");
        expect(t, "azzera le conchiglie di DoppiaX", "reset blockshell -> DoppiaX");
        expect(t, "dai 3 livelli battlepass a crakc", "give 3 ricompense:pass -> crack");
        expect(t, "dai 2 livelli di danno a Giulia, poi muta crack regola 3.4",
                "give 2 upgrade:damage -> Giulia", "mute regola 3.4 -> crack");
        expect(t, "verifica se crack usa la fly e dai 100 bc a DoppiaX",
                "CUSTOM \"verifica se crack usa la fly e dai 100 bc a DoppiaX\" target=crack");
        expect(t, "verifica se crack usa la fly, poi dai centocinquanta bc a DoppiaX",
                "CUSTOM \"verifica se crack usa la fly\" target=crack", "give 150 blockcoin -> DoppiaX");
        expect(t, "dai 10 palle a crack", "give 10 ball -> crack [INCERTO spec: standardball, doubleball, ghostball, explosionball, all]");
        expect(t, "dai 10 palle doppie a Luca", "give 10 ball:doubleball -> ?Luca [INCERTO target: LucaGamer, Lucas]");
        expect(t, "dai 10 coin a pippo", "give 10 coin -> ?pippo [INCERTO target: *");
        expect(t, "dai 5 coin a crack e a Giulia", "give 5 coin -> crack", "give 5 coin -> Giulia");
        expect(t, "metti a 30 le palle esplosive di crack", "set 30 ball:explosionball -> crack");
        expect(t, "dai 1000 palle tutte a crack", "give 1000 ball:all -> crack");
        expect(t, "rimuovi duemilacinquecento coin a DoppiaX", "remove 2500 coin -> DoppiaX");
        expect(t, "giva 10 coinz a crack", "give 10 coin -> crack");
        expect(t, "daiii 7 mineree a crack", "give 7 mine -> crack");
        expect(t, "dai 20 palle standard e ghost a crack",
                "give 20 ball:standardball -> ? [INCERTO target: ]", "give 20 ball:ghostball -> crack");
        expect(t, "togli 4 colpi potenziati a Giulia", "remove 4 power -> Giulia");
        expect(t, "dai una palla ghost a crack", "give 1 ball:ghostball -> crack");
        expect(t, "warna crak 2.1", "warn regola 2.1 -> crack");
        expect(t, "muta Lucs per la regola 4.1", "mute regola 4.1 -> Lucas");
        // ordini (v4): semplici, condizionali coi rami, custom
        orders(t, "se doppiaX ha comprato la miniera 5, toglietegli la miniera e dategli 3000 blockcoin, se invece non ce l'ha "
                + "date 200 coin a zcrask, se invece ha solo la miniera 3 dategli la miniera 4 togliendogli 3000 blockcoin, "
                + "se invece ha solo la miniera 2 non fare niente", "CONDITIONAL | [doppiaX ha comprato la miniera 5] remove 1 mine -> DoppiaX + give 3000 blockcoin -> DoppiaX | [non ce l'ha] give 200 coin -> zcrask | [ha solo la miniera 3] set 4 mine -> DoppiaX + remove 3000 blockcoin -> DoppiaX | [ha solo la miniera 2] non fare nulla | non fare nulla");
        orders(t, "dai 20 palle standard a DoppiaX togliendole a crack", "SIMPLE | give 20 ball:standardball -> DoppiaX + remove 20 ball:standardball -> crack");
        orders(t, "se crack ha piu' di 1000 coin togligliene 500, altrimenti dagli 100 coin", "CONDITIONAL | [crack ha piu' di 1000 coin] remove 500 coin -> crack | [altrimenti] give 100 coin -> crack | non fare nulla");
        orders(t, "nel caso Giulia abbia la stamina vuota riempigliela: setta la stamina di Giulia a 100", "CONDITIONAL | [Giulia abbia la stamina vuota riempigliela] set 100 stamina -> Giulia | non fare nulla");
        orders(t, "se crack ha usato la fly warnalo 1.2, se l'ha fatto di nuovo bannalo regola 2.1, se non ha fatto nulla non fare niente", "CONDITIONAL | [crack ha usato la fly] warn regola 1.2 -> crack | [l'ha fatto di nuovo] ban regola 2.1 -> crack | [non ha fatto nulla] non fare nulla | non fare nulla");
        orders(t, "verifica se zcrask usa la x-ray. dai 5 coin a Giulia", "CUSTOM \"verifica se zcrask usa la x-ray\" | ", "SIMPLE | give 5 coin -> Giulia");
        orders(t, "se Lucas ha meno di 3 palle ghost dagli 10 palle ghost; se invece ne ha gia' 3 togli 1 miniera a Lucas", "CONDITIONAL | [Lucas ha meno di 3 palle ghost] give 10 ball:ghostball -> Lucas | [ne ha gia' 3] remove 1 mine -> Lucas | non fare nulla");
        orders(t, "se DoppiaX ha il battlepass al livello 2 dagli 3 livelli battlepass e 50 bc altrimenti non fare nulla", "CONDITIONAL | [DoppiaX ha il battlepass al livello 2] give 3 ricompense:pass -> DoppiaX + give 50 blockcoin -> DoppiaX | non fare nulla");
        orders(t, "qualora crack abbia 0 blockshell dagli 64 blockshell, se invece ne ha almeno 10 azzera le blockshell", "CONDITIONAL | [crack abbia 0 blockshell] give 64 blockshell -> crack | [ne ha almeno 10] reset blockshell -> crack | non fare nulla");
        System.out.println(fails == 0 ? "\nTUTTO OK" : "\nFALLITI: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    private static void orders(TaskText t, String text, String... want) {
        List<TaskText.OrderText> got = t.parseOrders(text, PLAYERS);
        System.out.println("# " + text);
        boolean ok = got.size() == want.length;
        for (int i = 0; i < got.size(); i++) {
            String g = got.get(i).toString();
            boolean same = i < want.length && g.equals(want[i]);
            ok &= same;
            System.out.println("   " + (same ? "ok  " : "NO  ") + g + (same || i >= want.length ? "" : "\n       (atteso: " + want[i] + ")"));
        }
        if (!ok) {
            fails++;
        }
    }

    private static void expect(TaskText t, String text, String... want) {
        List<TaskText.Action> got = t.parse(text, PLAYERS);
        System.out.println("> " + text);
        boolean ok = got.size() == want.length;
        for (int i = 0; i < got.size(); i++) {
            String g = got.get(i).toString();
            boolean same = i < want.length && (want[i].endsWith("*") ? g.startsWith(want[i].substring(0, want[i].length() - 1)) : g.equals(want[i]));
            ok &= same;
            System.out.println("   " + (same ? "ok  " : "NO  ") + g + (same || i >= want.length ? "" : "   (atteso: " + want[i] + ")"));
        }
        for (int i = got.size(); i < want.length; i++) {
            System.out.println("   NO  (manca) " + want[i]);
        }
        if (!ok) {
            fails++;
        }
    }
}

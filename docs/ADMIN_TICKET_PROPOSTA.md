# PROPOSTA - nuovo /adminmania ticket (sezione TICKET dello staff)

Autore: Davide. Stato: FASE 2, codice scritto (`TicketStaffGui`); grafica di Sara in attesa (vedi 6).
Valgono sempre `docs/REGOLE_GUI.md` e `maniablock5/docs/design-team/GUIDA_STILE.md`.
Modello: GUI task (`TaskGui`), da cui si copiano struttura, popup e comportamento dei tasti.

---

## 1. Com'è oggi (niente di questo deve andare perso)

### 1.1 Ingresso
- `AdminCommand.onCommand`: `/adminmania ticket ...` passa subito a `Tickets.staffCommand` (prima dei controlli di livello).
- Accesso: `Tickets.isStaff` = OP oppure un permesso `blockmania.role.<ruolo>` di `adminmania.roles`.
- Sottocomandi: `aperti|open` (quello di default), `miei|mine`, `chiusi|closed`, `<numero>` (apre la chat del ticket), `test` (crea il ticket di prova "GiocatoreTest", categoria bug, con 3 messaggi, e apre la chat).
- Tab complete: `aperti, miei, chiusi, test`.
- Apre anche `seeStaff` (salva il nick dello staffer in `staffSeen`, che serve per il popup "tagga", e scarica la faccia).

### 1.2 La lista staff di oggi (`openStaffList`): perché "fa cagare"
- È un popup lista ticket-5 (U+3E04) messo sopra una chat ticket-3 **vuota e scurita**. Non c'è una schermata principale, e lo sfondo non vuol dire niente.
- Ha solo 3 filtri (tasto sinistro = su, destro = giù): "tutti gli aperti", "i tuoi aperti" (`t.staff` ti contiene), "chiusi da te" (`closedByUuid`). Non si possono vedere **tutti** i chiusi, non c'è la ricerca con Q e non si filtra per sezione.
- La lore degli item è un muro di testo, fino a 11 righe: player, sezione, stato, ultimo, staff, risposto da, aperto il, chiuso il, valutazione, non letti, istruzione. Dice "chiuso il" ma **non dice da chi**.
- Il pulsante X si chiama "chiudi" e chiude tutto. Il pulsante "i" non serve a niente.
- Item: modelli `blockmania:ticket_open / ticket_wait / ticket_closed` (questi vanno bene).

### 1.3 Funzioni dello staff dentro la chat (ticket-3, `openChat` con `staffSide`). RESTANO TUTTE
- Testa del player (slot 0): lore `staffInfoLines` (risposto da, aperto il, chiuso il, valutazione). Il click apre `UserProfileGui` del player.
- Su/giù per scorrere i messaggi. "Scrivi messaggio": sinistro = incudine/chat (`askText`), destro = popup link.
- Fila in basso: chiudi ticket (con conferma ticket-6), cambia sezione (popup ticket-4 con 3 colonne), tagga staffer (popup ticket-5: teste con il carico `x/max-staff-open`, poi una nota scritta che diventa messaggio "note" visibile solo allo staff), 6 messaggi pronti `CANNED` (risolto, accettata, chiusura, non_valida, prove, attendi). "chiusura" fa partire `autoClose` dopo `auto-close-hours`; "prove" manda al player il link di imgur.
- Popup link: filtri tutti / dal player / dallo staff, poi apre `TicketDialog`.
- Indietro: torna alla lista staff (`lastStaffListMode`) oppure alla main del player.

### 1.4 Regole di funzionamento (logica, invariata)
- Uno staffer **prende** un ticket quando gli scrive per la prima volta (`postMessage`: lo aggiunge a `t.staff`). C'è un limite di `tickets.max-staff-open` (9) ticket aperti per staffer.
- Stato: aperto (verde), in attesa (giallo: nessuno staff oppure chiusura automatica avviata), chiuso (rosso).
- Chiusura: messaggio di sistema. Il player deve dare le stelle entro `rate-hours`, altrimenti riceve 5 stelle automatiche + `warn-command`. Avviso 1 ora prima.
- Notifiche in chat allo staff per un ticket nuovo o un messaggio nuovo, con click su `/ticket <id>`.
- `MovementLog` (TICKET/STAFF), `StaffStats` / `StaffProfileGui` (categoria TICKET, stelle) leggono `ticketsRepliedBy`.
- Config `tickets.*`: max-staff-open, auto-close-hours, rate-hours, warn-command, anvil-shift, canned.*.

---

## 2. Nuova struttura

Nuova classe `TicketStaffGui` (copia della struttura di `TaskGui`). La logica dei ticket resta in `Tickets`: la GUI la usa tramite metodi pubblici di sola lettura e azioni (vedi 5).

### 2.1 Come si apre
- `/adminmania ticket` apre la **schermata principale TICKET**.
- `/adminmania ticket aperti|miei|chiusi` apre la schermata principale con sopra il popup lista di quella sezione (scorciatoie mantenute).
- `/adminmania ticket <numero>` (anche `#12` o il codice di 6 caratteri) apre la chat, come oggi.
- `/adminmania ticket test` resta com'è.
- **STAFFBOARD**: un pulsante "TICKET" apre `TicketStaffGui.open(p, back)`. `back` è un `Runnable` (lambda esplicito `() -> { staffBoard.open(p); }`):
  - con `back != null` la X diventa "indietro" e torna alla staffboard;
  - da comando `back == null` e la X chiude.
  - La GUI ticket non conosce la staffboard: riceve solo il `Runnable`. Così la staffboard si può fare dopo, senza toccare i ticket. Lo stesso schema vale per task e profili (`TaskGui.open(p, back)` andrà aggiunto quando faremo la staffboard).
  - Il `back` viene salvato per player (mappa `UUID -> Runnable`) così sopravvive ai giri tra popup, chat e incudine. Si toglie alla chiusura vera della GUI (come `advFilters` in TaskGui) e al quit.

### 2.2 Schermata principale (stesso layout di task.png: 3 card + 2 pulsanti in basso)

| Zona (slot come TaskGui) | Pulsante | Click | Lore (compatta) |
|---|---|---|---|
| X (0,1) | chiudi / indietro | chiude, oppure `back` | - |
| barra frecce (2..6) | "1/1" sempre; item "il tuo carico" | apre il ticket più recente con messaggi non letti tra i tuoi (se c'è) | `presi da te: 3/9`, `non letti: 5`, `in attesa del player: 1` |
| i (7,8) | info | - | `prendi: rispondi al ticket`, `massimo: 9 a testa`, `chiusura auto: 6h dopo il messaggio`, `voto: entro 6h, poi 5 stelle` |
| card sinistra (S_LIST) | **APERTI** | popup lista "aperti" | `aperti: 12`, `da prendere: 4` |
| card centrale (S_CREATE) | **MIEI** | popup lista "miei" | `presi da te: 3/9`, `non letti: 5` |
| card destra (S_TRASH) | **CHIUSI** | popup lista "chiusi" | `chiusi: 230`, `chiusi da te: 41` |
| basso sx (S_DONE) | **STELLE** (valutazioni; "VALUTAZIONI" e' 61 px e non sta nei 59) | popup lista valutazioni | `media: 4,6`, `da valutare: 2` |
| basso dx (S_LOG) | **RESOCONTO** | popup lista registro | `voci: 812`, `mostra: tutto` (admin) / `le tue` |

Non c'è il CESTINO: i ticket non si eliminano (oggi non esiste e non lo aggiungo). Se un giorno servirà, si userà Q + conferma, come vogliono le regole.

### 2.3 Popup lista (U+E119, `listPanel` copiato da TaskGui)
Sotto c'è la schermata TICKET scurita, con il titolo "TICKET" identico e solo più scuro. X = indietro alla main. Pulsante filtro (L_BTN): sinistro = su, destro = giù, **Q = cerca** (incudine: `#12`, `12`, codice `AB3K9Z` o nick, anche scritto male, come `parseAdvanced`). Il filtro cercato si aggiunge in fondo, già selezionato, e sparisce quando la GUI si chiude. Barra descrittiva = nome del filtro. Numero pagina sempre presente. 21 per pagina, i più recenti prima.

| Lista | Filtri | Item |
|---|---|---|
| APERTI | Tutti, Da prendere (nessuno staff), In attesa (chiusura auto avviata), Assistenza, Bug, Report | modello stato |
| MIEI | Tutti, Non letti, In attesa del player, Taggati da altri (aggiunto con nota) | modello stato |
| CHIUSI | Tutti, Chiusi da me, Chiusi in automatico, Da valutare, Assistenza, Bug, Report | `ticket_closed` |
| VALUTAZIONI | Tutte, 5..1 stelle, Automatiche, Dove ho risposto io | `ticket_closed` |
| RESOCONTO | Tutto, Aperti, Risposte, Messaggi pronti, Sezione cambiata, Tag, Chiusi, Valutati | icona per evento (sotto) |

**Lore di un ticket** (al massimo 6-7 righe, formato "&7etichetta: &fvalore"):
```
Ticket #12 - bug                      (nome: &f&l, la sezione in &7)
player: Mario
aperto il: 24/11/2026 14:52
staff: Luca, Anna            | da prendere   (aperti)
ultimo: 24/11/2026 15:10 da Luca
non letti: 3                                 (solo se > 0)
chiusura auto: 24/11/2026 21:10              (solo se in attesa)
chiuso da Luca il 24/11/2026 16:00           (chiusi; "sistema" se automatico)
valutazione: ★★★★☆ (4/5) | da dare entro 24/11/2026 22:00 | 5/5 automatica
                                             (riga vuota)
[sinistro] apri  [destro] profilo
```
- Sinistro: apre la chat (ticket-3). L'indietro della chat torna alla **stessa lista, filtro e pagina** da cui l'hai aperta (sostituisce `lastStaffListMode`, che oggi riapre sempre la pagina 0).
- Destro: profilo del player (`UserProfileGui`, che funziona anche offline).
- Q sugli item: niente (non si elimina).
- Date sempre `dd/MM/yyyy HH:mm` Europe/Rome. Oggi `Tickets.DATE` usa il fuso di sistema: va uniformato a `DATE_FULL`.

**RESOCONTO**: non serve un registro nuovo. Si ricostruisce dai messaggi `sys` / `note` / `canned` / staff dei ticket. Voce: `evento - ticket #12`, lore `da: Luca`, `quando: ...`, una riga di dettaglio. Il non admin vede solo i ticket a cui ha risposto. Sinistro = apre la chat.

**VALUTAZIONI, vista per staffer** (filtro aggiunto con Q su un nick di staff): media e numero di ticket dello staffer nel nome del filtro. Va benissimo anche solo la lista: niente cartella U+E11E, perché il dettaglio di un ticket è già la chat.

### 2.4 Conferme e scelte
- Chiudere un ticket resta nella chat, con la sua conferma (ticket-6).
- Le nuove conferme della sezione usano `UserProfilePowers.confirmOver` sopra la schermata TICKET scurita (`bgHere`). Per ora c'è solo quella della presa in carico facoltativa qui sotto ("Prendere il ticket #12?"). Se lo staffer è al limite, compare il messaggio d'errore di `postMessage`.
- **Prendere in carico senza scrivere** (approvato): in ogni lista, shift + click su un ticket aperto non tuo = "prendi" con conferma (aggiunge a `t.staff`, rispetta il limite, nota visibile solo allo staff "X ha preso in carico il ticket").

### 2.5 Allineamento della chat (fase 3, facoltativa ma consigliata dalle regole "ovunque")
- Popup link e tagga: aggiungere Q cerca sul filtro e il numero pagina (c'è già), e accorciare le lore.
- `staffInfoLines`: "chiuso da X il ..." al posto di "chiuso il ...".
- Togliere la vecchia lista fatta col popup ticket-5 sopra la chat vuota: `openStaffList` sparisce e i sottocomandi `aperti|miei|chiusi` chiamano la nuova GUI.

---

## 3. Grafica

### 3.1 Si riusa così com'è
- `task.png` (U+E180, font `minecraft:task`) come struttura: cornice, header, 3 card, 2 pulsanti in basso, inventario corretto. Si riusano anche `inv_full` U+E000, `background()` / `drawDimmed` e `mainLabels` (scritte dei pulsanti in basso col font `tiny_y123`).
- Popup lista U+E119 (`userprofile3`) con barra `tiny3`, numeri pagina U+E4A0 / U+E4B0.
- Conferma `confirmOver` (U+E208 / U+E209, font mines). Popup frecce U+E11F se serve una scelta.
- Item `ticket_open / ticket_wait / ticket_closed / ticket_link`, `icone-profilo/ticket.png` U+E305.
- La chat ticket-3 e i suoi popup ticket-4/5/6/7/8 restano come sono.
- Cartella U+E11E: **non serve** (vedi 2.3).

### 3.2 Cosa serve a Sara (nuovo)
1. **`custom-gui/ticket/ticket_staff.png`**: copia di `task.png` con solo l'interno cambiato:
   - 3 icone delle card ridisegnate a 16x16 come le cartelline: **APERTI** = vassoio/posta con ticket, **MIEI** = ticket con omino/spilla, **CHIUSI** = ticket con lucchetto;
   - etichette disegnate APERTI / MIEI / CHIUSI con i glifi di `tiny.png`. In alternativa, strisce vuote e scritte del plugin come per COMPLETATE/RESOCONTO: preferisco la seconda, così non serve rifare il PNG se cambiano i nomi;
   - le 2 icone 12x15 dei pulsanti in basso: **VALUTAZIONI** = stella con ticket, **RESOCONTO** = cartellina con grafico (si riusa quella di task).
   - Nuovo carattere nel font `minecraft:task` (es. U+E181), stesse misure di task.png, così vanno bene `inv_full` U+E000 e tutti i centri.
2. **Icone item per il RESOCONTO** (16x16, contorno tinto `#221628`), `blockmania:ticket_ev_*`: aperto, risposta, messaggio pronto, sezione, tag, chiuso, valutato. Si possono rimandare: all'inizio si usano `ticket_open/wait/closed/link`.
3. **STAFFBOARD** (quando la faremo): un'icona "ticket" 16x16 per il suo pulsante. Si può riusare U+E305 ridisegnata alla misura giusta, mai scalata.

---

## 4. Funzioni vecchie -> dove vanno

| Oggi | Nuovo |
|---|---|
| `/adminmania ticket` (lista aperti) | schermata principale, poi APERTI |
| `aperti` / `miei` / `chiusi` | stessa cosa, aperta direttamente sulla lista |
| filtro "tutti gli aperti" | APERTI, Tutti |
| filtro "i tuoi aperti" | MIEI, Tutti |
| filtro "chiusi da te" | CHIUSI, Chiusi da me |
| `<numero>` | uguale, in più `#numero` e codice |
| `test` | uguale |
| lore lista (staff, risposto da, date, voto, non letti) | lore compatta 2.3 + chi ha chiuso |
| chat staff, chiudi, sezione, tag, canned, link, profilo | invariati (in Tickets) |
| indietro dalla chat | torna alla lista/filtro/pagina di partenza |
| tab complete | `aperti, miei, chiusi, test` + numeri dei ticket aperti |

---

## 5. Classi

- **Nuova `TicketStaffGui implements Listener`** (registrata in `BlockMania` come `TaskGui`, getter `getTicketStaffGui()`). Contiene: Holder con `actions / rightActions / dropActions / under / dead`, `open(p)` e `open(p, back)`, `background`, `bgHere`, `listPanel`, filtri avanzati (`Adv`, `askAdvanced`), `onClick` (DROP = Q) / `onDrag` / `onClose` / `onQuit`, `smallText`, `navText`, `put`, `icon`, `pane`. Tutto copiato da TaskGui, scheduler sempre `() -> { ... }`.
- **`Tickets`** (logica):
  - aggiungere una vista pubblica di sola lettura, come `StaffView`: `record StaffTicket(id, code, ownerName, owner, category, status, created, updated, staffNames, lastStaffName, unread, autoClose, closedAt, closedBy, rating, ratedAuto, rateDeadline)` e `List<StaffTicket> staffTickets(UUID viewer)`;
  - `List<LogLine> staffLog(UUID viewerOrNull)` per il RESOCONTO;
  - `openChatFrom(p, id, Runnable back)`, così l'indietro della chat richiama la lista giusta;
  - facoltativo: `take(p, id)`;
  - `staffCommand` chiama `TicketStaffGui`, `openStaffList` si toglie;
  - `DATE` passa a Europe/Rome e "chiuso da" va in `staffInfoLines`.
- **`AdminCommand`**: nessuna modifica (passa già tutto a `Tickets.staffCommand`).
- **Pack (Sara)**: `ticket_staff.png` + carattere nel font `task.json` + voce in `README_CARTELLE.txt`; icone `ticket_ev_*` (facoltative).
- **Futuro `StaffBoard`**: un pulsante chiama `plugin.getTicketStaffGui().open(p, () -> { open(p); })`.

---

## 6. Grafica di Sara (applicata)

Valori da `maniablock5/docs/TICKET_STAFF.md`, costanti `PACK` in `TicketStaffGui`: sfondo `ticket_staff.png` = U+E181 del font
`minecraft:task`; etichette card col font `tiny_y95` (centri 40 / 94 / 148, max 41); VALUTAZIONI (centro 58, max 62) e
RESOCONTO (centro 145, max 60) col font `tiny_y123`; icone del resoconto `ticket_ev_open / reply / canned / section / tag /
closed / rated` ("preso in carico" usa `ticket_open`). L'icona `blockmania:staffboard` serve alla futura STAFFBOARD.
Font aggiunto da Davide nel pack: `font/tiny_y147.json` (= tiny_y123 con ascent -119/-120) per il "1/1" del popup "cambia sezione" (ticket-4).

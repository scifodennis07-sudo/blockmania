# Audit GUI e popup (rispetto a docs/REGOLE_GUI.md)

Revisione: Elena (reviewer). Data: 24/11/2026. Nessun file di codice modificato.
Base: sorgenti `src/main/java/it/blockmania`, pack `maniablock5`, screenshot dell'utente
(67a865bc / 11718785 lista ticket, 633e0706 / e287f6c3 profilo, 3a48947f ticket principale).

Priorità: **P1** = visibile e già segnalato o blocca una funzione; **P2** = regola violata in modo visibile;
**P3** = rifinitura, rischio o da decidere.

Regole (abbreviazioni usate sotto):
- **R-TIT** titolo identico con popup aperto/chiuso (testo e x uguali, solo scurito; il popup non scrive un suo titolo)
- **R-INV** inventario del player non scurito · **R-BG** sfondo scurito uguale al pixel alla GUI normale
- **R-BAR** barra descrittiva del popup sempre con un testo, che non esce mai dalla barra · **R-DESC** descrizione specifica, punteggiatura
- **R-ESC** popup chiusi solo dai pulsanti · **R-Y91** scritte dei pulsanti in tiny_y91
- **R-NAV** "N/M" sempre (anche 1/1), centrato nella barra frecce · **R-FIL** sinistro = su, destro = giù, extra su Q
- **R-Q** Q sul filtro = filtro avanzato · **R-DEL** eliminare solo con Q + conferma
- **R-LORE** lore compatte · **R-KEY** icone dei tasti al posto delle parole · **R-DATA** dd/MM/yyyy HH:mm
- **R-STATO** completato/chiuso/finito: da chi + quando · **R-CHAT** niente istruzioni inutili in chat
- **R-OFF** nei profili si fa tutto anche col player offline

---

## 0. Problemi trasversali (leggere prima)

### 0.1 Perché "PROFILO ZCRQCK" si sposta (screenshot 633e0706 / e287f6c3)
Convenzione di tutti i titoli: dopo l'immagine a pagina intera dichiarata 179 px (reale 186) il cursore di `GuiTitle`
è **7 px indietro** rispetto al reale; `topTitle` (dx 0) scrive quindi 7 px più a destra di quanto creda, e `topTitleDim`
(dx +7, cursore reale) scrive nello stesso punto. Questo vale finché ogni pezzo dopo l'immagine mantiene lo scarto
di 7 px.

In `UserProfileGui.drawPageNumber` (riga 439) il numero pagina del **titolo normale** si scrive a `-15 + PAGNUM_SHIFT`
(= -22, cioè già spostato di -7) **e** con l'avanzamento dichiarato `PAGNUM_DECL = 98` (= reale 105 - 7, riga 434).
Così i 7 px si tolgono due volte: dopo il numero pagina lo scarto passa da 7 a **14 px** e il `topTitle` della riga 445
finisce **7 px a destra** del centro della barra. Nello sfondo dei popup (`real = true`: x -15, avanzamento 105)
lo scarto resta giusto e `topTitleDim` è centrato. Quindi **la posizione corretta è quella del popup**: è sbagliato il
titolo normale (nello screenshot è a 860-1100 px su una barra con centro 960 = 20 px schermo = circa 7 px GUI).

Correzione: in `drawPageNumber`, nel percorso normale avanzamento reale e x -22:
`g.at(real ? -15 : -22, ..., FONT_PAGNUM, PAGNUM_REAL)` (togliere `PAGNUM_DECL`).
È esattamente come fa già `StaffProfileGui.overlay` (righe 532-541), che infatti è giusto.

Stesso errore cercato in tutte le GUI (overlay a -22 con avanzamento dichiarato, oppure titolo normale e titolo
dello sfondo disegnati con metodi o x diversi):
- **UserProfileGui:439**: VIOLAZIONE (vedi sopra), interessa le 3 pagine del profilo e tutti i popup aperti dal profilo.
- **RoleGui:117**: barra ruolo a `IMG + ROLE_SHIFT` (-22) con `ADV_ROLE = 121` (dichiarato): stesso doppio -7.
  Oggi non fa danni perché dopo non si scrive niente. P3: usare l'avanzamento reale 128.
- **StaffPage2Glyphs:8**: il commento dice "normale g.at(-15 - 7, ch, font, real - 7)": è proprio la regola sbagliata.
  Il codice (`StaffProfileGui.overlay`) usa invece `real`, che è giusto. P3: correggere il commento perché nessuno lo copi.
- OK (stesso testo e stesso metodo con popup aperto/chiuso): StaffProfileGui (243/571), RulesGui (260/569),
  RuleDetailGui (551/577), TaskGui (299/424), UserProfileHistory (drawBg riusa drawPage), MinesGui/UserProfileMines
  (fontTitle: stesso topAndPage), UserProfileRewards (riusa il Component `v.title()`), UserProfileUpgrades
  (drawTexts con -7/+7), BlockVariantsGui/ItemsGui (stesso `UpgradeManager.barTitle`), Tickets.popup (2008-2010).

### 0.2 R-ESC: popup che si chiudono con ESC
Solo la conferma di `UserProfilePowers` si riapre se chiusa senza scegliere (`onClose` 1357-1372, `ch.reopens`).
Tutti gli altri popup si chiudono con ESC/E: popup comandi (`UserProfilePowers` kind "cmds"), conferma di
`UserProfileMines`, liste di `ProfileLists`, `RulePopups`, `StaffProfileGui` (popup lista, **nessun** onClose),
`RoleGui`, `TaskGui` (folder/listPanel), popup `Tickets` (ticket-2/4/5/6/8), `BlockVariantsGui`, `TicketDialog`.
Correzione unica: stesso schema della conferma (flag `chosen`/`dead`, riapertura al tick dopo se il top inventory è
CRAFTING e non c'è `switching`, limite di riaperture). Conviene un helper comune e usarlo in ogni holder di popup.

### 0.3 R-BG: popup sopra popup
Quando una conferma si apre sopra un altro popup, lo sfondo deve contenere **tutto** il popup di sotto (barra
descrittiva, numero pagina, icone). Oggi si disegna solo l'immagine del pannello:
- `UserProfilePowers.confirm` 494-511 (conferma sopra popup comandi: mancano `tinyBar` e `navText`).
- `StaffProfileGui.bgPopup` 1254-1258 (manca barra, "N/M", icone delle righe).

### 0.4 Profili offline (R-OFF), elenco completo
Vedi i singoli file. In sintesi: miniere, potenziamenti, ricompense ed exp del profilo **non si aprono** offline
(UserProfileGui 905-908 + controlli in UserProfileRewards/Upgrades/Mines); `infinite` stamina e `setuses`/`power_uses`
non sono disponibili offline; le lore delle icone mostrano "stato: offline" e tolgono l'istruzione anche dove
l'azione funziona (coda `StaffTasks.applyOrQueue`); le GUI live si chiudono se il target esce; le notifiche
disciplinari allo staffer offline si perdono; `/adminmania` scambia un target offline per "te stesso".

### 0.5 Blocco di compilazione trovato durante l'audit
`BlockMania.java:720` e `Tickets.java:2651` usano `TicketStaffGui` (`getTicketStaffGui()`, `gui.open`, `gui.openSection`),
ma **la classe non esiste** in `src/`. Il plugin non compila per questo. La schermata principale di `/adminmania ticket`
quindi non si può verificare; oggi il ripiego è `Tickets.openStaffList` (verificata sotto). **P1** per chi ha Tickets/BlockMania.

---

## 1. Tickets.java (12 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P1 | R-NAV | 1262-1275 `openMain` | La barra frecce di ticket.png è vuota (screenshot 3a48947f): manca "1/1". | Dopo `topTitle` chiamare `navText(g, 1, 1, <centro barra frecce di ticket.png>)` (stessa barra del profilo: centro x 94/95, verificare con l'immagine). |
| 2 | P1 | R-TIT | 1372 `openList` | ticket-2 è un popup sopra la pagina principale, ma il titolo cambia: "LISTA TICKET", "TICKET REPORT"... ed è a colori pieni. | Scrivere `"ticket"` come la GUI principale, scurito: `GuiTitle u = new GuiTitle(plugin); topTitle(plugin, u, "ticket"); g.atComponent(0, tint(u.build(), null), u.cursor());` (come in `popup()` 2008-2010). |
| 3 | P1 | R-BAR | 1371-1373 | La barra descrittiva del popup (tra X e filtro) è vuota (screenshot 67a865bc/11718785). | `tinyText(g, FILTERS[filter], <cx barra ticket-2 ≈95>, true, BAR, BAR_SH)` con il font della barra di ticket-2 (controllare la y rispetto a ticket-5). Testo: "tutti", "aperti", ... |
| 4 | P2 | R-BG | 1371 | Sotto ticket-2 la pagina principale è solo l'immagine "cotta": mancano chip di stato, sezione, faccia e pallino non letti del riquadro "ultimo ticket" (righe 1264-1274). | Disegnare il contenuto di openMain scurito sotto il popup (come `popupOverChat`), saltando quello coperto dal popup. |
| 5 | P1 | R-BAR, R-NAV | 1967-1985 `openCategory` | ticket-4 ha la barra in alto e la barra frecce: entrambe restano vuote. | Dopo `popupOverChat`: `tinyText(g, "sezione", ...)` (o il nome della sezione attuale) e `navText(g, 1, 1, POPUP_CX, NAV2, NAV2_SH)`. |
| 6 | P2 | R-DESC | 1940-1961 `openConfirm` | ticket-6/8 hanno la scritta fissa "Conferma o annulla il comando.", identica per "chiudi ticket" e "conferma valutazione". | Usare il pannello senza scritta (come `confirmOver`/`drawDescription`) con testi specifici: "Chiudere il ticket #N?" / "Dare N stelle al ticket #N?". Pulsanti in tiny_y91. |
| 7 | P1 | R-STATO | 1298, 1393, 1671, 1705, 2207 | "stato: chiuso" senza chi e quando (lista, ultimo ticket, pulsante scrivi, lucchetto, lista staff). I dati ci sono (`closedBy`, `closedAt`). | Aggiungere `"&7chiuso da: &f" + (t.closedBy.isBlank() ? "sistema" : t.closedBy)` e `"&7chiuso il: &f" + DATE_FULL.format(...)`. Chiusura automatica: "chiuso da: automatico". |
| 8 | P1 | R-STATO | 937 `staffInfoLines` | C'è "chiuso il" ma non "chiuso da". | Aggiungere la riga "chiuso da" prima di "chiuso il". |
| 9 | P2 | R-Q | 1376-1383, 2074-2080, 2189-2196 | Filtri della lista, dei link e della lista staff senza Q = cerca (per codice ticket / nick). | Q sul filtro: incudine "cerca codice o nick", filtro temporaneo che sparisce alla chiusura (come `TaskGui.advFilters`). Riga istruzioni: "sinistro su &8\| &edestro giù &8\| &eQ cerca". |
| 10 | P2 | R-ESC | 2541-2566 `onClose` | Tutti i popup ticket si chiudono con ESC. | Vedi 0.2. |
| 11 | P3 | R-CHAT | 1662 + 2441-2447 | Il player vede "clicca per aprire il profilo" sulla sua testa, poi riceve "Il profilo del giocatore non e disponibile.". | Istruzione e azione solo per lo staff. |
| 12 | P3 | R-DESC | 1766, 1771, 1787, 1850, 1863, 2139, 2446, 2646 | Accenti mancanti: "e vuoto", "gia", "rispondera", "e chiuso", "e solo". | "è", "già", "risponderà". |

OK: date (`DATE`, `DATE_FULL` con anno), R-FIL (2357-2363), R-KEY (le righe "sinistro su | destro giù" diventano icone, si vede negli screenshot),
R-INV (ticket-3..8 nascondono l'inventario in modo voluto), lista staff (`popup()`: titolo "ticket" scurito, barra e "1/1" presenti),
link e tag (barra "link"/"tag staff", "1/1"), messaggi di sistema centrati (1625).

## 2. TicketDialog.java (2 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P2 | R-TIT, R-BG | 53-54, 189-193 | Lo sfondo è un'immagine fissa (clicklink E000/E001): mancano il codice del ticket nella barra in alto e la chat scurita. Il titolo non è identico alla GUI di sotto. | Se il Dialog non permette di comporre il titolo, aprire i link con un popup inventario sopra `popupOverChat` (ticket-5) invece del Dialog. Altrimenti accettarlo come eccezione e dirlo all'utente. |
| 2 | P3 | R-ESC | 228 `canCloseWithEscape(true)` | ESC chiude (= annulla). | Il Dialog richiede un'azione di uscita: lasciare l'annulla, ma documentare l'eccezione. |

## 3. UserProfileGui.java (9 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P1 | R-TIT | 434, 439 (`drawPageNumber`), effetto a 445 | Titolo normale 7 px a destra rispetto a popup/centro barra (vedi 0.1). | `g.at(real ? -15 : -22, "&f" + glyph, FONT_PAGNUM, PAGNUM_REAL)`; eliminare `PAGNUM_DECL`. |
| 2 | P1 | R-OFF | 528-533, 905-908 | Miniere, potenziamenti, ricompense, exp: "Il giocatore deve essere online." | Passare l'UUID (non `Player`) alle GUI del profilo e leggere/scrivere dati salvati o in coda (vedi file 4-7). |
| 3 | P1 | R-OFF | 735-740 `counterItem`, 749-750 `staminaItem`, 766-771 `powerItem`, 529-541 | Offline la lore dice "stato: offline" e toglie l'istruzione, ma `itemMenu` e `staminaMenu` funzionano (coda). Sembra bloccato. | Stessa lore online/offline. Offline aggiungere "&7modifica: &fal prossimo accesso" e lasciare "clicca per i comandi". Stamina offline: mostrare l'ultima percentuale salvata. |
| 4 | P2 | R-OFF | 1022-1023, 1054 | Offline manca "infinite" nella stamina. | Mettere in coda anche `stamina_mode infinite` (`applyOrQueue`). |
| 5 | P2 | R-OFF | 1369-1374 | Offline "da offline non si puo' cambiare questo contatore" (setuses / power_uses); se `StaffTasks` è null c'è "il giocatore e' offline". | Aggiungere `power_uses` alla coda offline; senza StaffTasks scrivere sul contatore salvato (`PlayerCounters` per UUID). |
| 6 | P3 | R-OFF | 1077-1079 | "Il giocatore e' offline: stamina non disponibile." quando `Power` o `StaffTasks` mancano. | Dopo la correzione 4 questo ramo serve solo per un errore interno: cambiare il testo (non deve parlare di offline). |
| 7 | P2 | R-KEY | 506-507, 563-564 | Lore informazioni "&7sanzioni: &fsinistro comandi / destro cronologia": le righe grigie non sono istruzioni e le parole non diventano icone. | Riga istruzione in fondo: `"&esinistro comandi &8\| &edestro cronologia"`. |
| 8 | P2 | R-BAR | 1024, 1127 | Etichette "stamina di NICK" / "mute di NICK": nella barra stretta (58 px) `fit` toglie "di" e poi taglia il nick lettera per lettera ("MUTE ZCRQC"). | Etichetta senza nick (il nick è già nel titolo): "stamina", "mute", "ban", "warn". |
| 9 | P3 | R-CHAT | 579, 581, 1002 | "cambia ruolo" e "aggiungi vip" ("in arrivo") mandano "non ancora disponibile." in chat. | Niente azione e niente messaggio, oppure solo la lore. |

OK: R-NAV (pagnum "1/3", "2/3", "3/3" sempre presente), R-INV (`INV_LOW`), conferme con `confirmOver` e descrizioni specifiche
(944, 970, 1042, 1152, 1176, 1348), date con anno (132), R-FIL n/a, R-DEL n/a.

## 4. UserProfilePowers.java (4 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P1 | R-BAR | 197 (`BAR_MAX = 58`), 933-951 `tinyBar`, 1006-1033 `fit` | "blockshell" misura 60 px, `fit` taglia l'ultima lettera: la barra mostra "BLOCKSHEL" (screenshot e287f6c3). Il taglio lettera per lettera rovina la parola. | Misurare la larghezza visibile (`tinyAdvance - 1`) contro l'interno reale (62 px - margine 1): `BAR_MAX = 60`. Se non basta, usare un'abbreviazione fissa (mappa nome → testo corto) o la barra larga, mai tagliare a metà parola. |
| 2 | P2 | R-BG | 494-511 `confirm` | Conferma sopra il popup comandi: sotto si vede l'immagine del popup senza testo della barra e senza "N/M". | Salvare nel Holder `label`, `page`, `pages` e ridisegnare `tinyBar` + `navText` prima del titolo (stesso ordine di `showCommands`). |
| 3 | P2 | R-ESC | 1357-1372 | Solo "confirm" si riapre, il popup "cmds" si chiude con ESC. | Stessa riapertura per `kind = "cmds"` (vedi 0.2). |
| 4 | P2 | R-CHAT, R-LORE | 864-868 | Pulsante "informazioni" con lore vuota; il click manda in chat "scegli con le icone, frecce per cambiare pagina, X per tornare". | Lore con dati utili ("&7comandi: &fN", "&7pagina: &fx/y"), niente messaggio in chat. |

OK: R-TIT (`drawDimmed` + `redrawTitleDim` sopra le immagini a pagina intera), R-INV (`drawInventoryFull`), R-NAV (`navText` sempre, 847),
R-Y91 (`drawLabel` 657), descrizione centrata su 1-3 righe (617-653), conferma anti doppio click.

## 5. UserProfileRewards.java (2 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P1 | R-OFF | 87-91 `open`, 112-116 `openExp`, 99-102 `expLore` | Ricompense ed exp solo online ("deve essere online", lore "exp: offline"). | API di Rewards per UUID su dati salvati (vedi Rewards #4); modifiche offline in coda (`StaffTasks.applyOrQueue` con what "ricompense"/"exp"). |
| 2 | P1 | R-OFF | 161-174 `targetOf` | Se il target esce mentre lo staff è nella GUI: "non e' piu' online" e la GUI si chiude. | Con la correzione 1 non serve più: continuare sui dati salvati. |

OK: R-TIT (riusa `v.title()` + `titleRedraw`), R-INV (`INV_LOW`), conferme specifiche ("Azzerare ... di NICK?").

## 6. Rewards.java (4 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P1 | R-TIT | 1105-1112 `titleLevels` | ricompense-2/3 sono popup sopra la pagina principale (vedi pack), ma il titolo diventa "BATTLEPASS", "BLOCCHI SPACCATI"... a colori pieni. Stesso errore dei ticket. | Titolo "RICOMPENSE" (o "RICOMPENSE DI NICK" nella vista staff) scurito; il nome della tipologia va nella barra verde del popup insieme a "livello N" oppure nella barra del popup. |
| 2 | P2 | R-BG | 1107-1108 | Sotto il popup livelli la pagina principale è "cotta" nell'immagine: tacchette e numeri (titleMain 1079-1095) non ci sono. | Disegnare titleMain scurito sotto il popup (come MinesGui.fontTitle) o accettare l'immagine fissa solo se uguale al pixel. |
| 3 | P3 | R-BAR | 1029-1040, 1050-1052 | Nella vista staff il nick si accorcia lettera per lettera ("RICOMPENSE DI ZCRQ"). | Ordine di ripiego come `UpgradeManager.barTitle` ("RICOMPENSE DI X" → "RICOMP X" → "RICOMPENSE"). |
| 4 | P1 | R-OFF (causa) | `reached(Player,..)`, `setReached(Player,..)`, `staffExpLines(Player)`, `staffView(Player,..)` | Tutta l'API lavora su `Player` online: è il motivo per cui il profilo non può gestire le ricompense offline. | Overload per UUID su dati salvati (file/PDC offline) + coda al login. |

OK: R-NAV (pagina principale "N/M" 1097-1100, livelli `navNumber`), giornaliera senza barra frecce (immagine senza frecce).

## 7. UserProfileUpgrades.java (3 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P1 | R-OFF | 76-79 | "Il giocatore deve essere online per gestire i potenziamenti." | Livelli letti/scritti per UUID; modifiche offline in coda. |
| 2 | P1 | R-OFF | 129-134 `startLive` | Se il target esce: "Il giocatore e' uscito dal server." e GUI chiusa. | Continuare offline (dati salvati). |
| 3 | P3 | R-BG (rischio) | 186-201 `drawBackdrop` | Lo sfondo ricostruisce l'immagine togliendo `{shift}` e codici da `title.text`. Con il valore di default del config (`"&8potenziamenti"`, config.yml 658 e resources 1779) disegnerebbe la parola "potenziamenti" a -15, diversa dal titolo normale. | Verificare il config in produzione; meglio leggere il glifo da una chiave dedicata (`title.image`) usata sia dal titolo che dallo sfondo. |

OK: R-NAV ("N/M" sempre, 160-175), R-TIT (`titleRedraw`), R-INV (`INV_HIGH`), conferma azzera specifica.

## 8. UserProfileMines.java (2 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P1 | R-OFF | 195-202 `targetOf`, 345-349 `open`, 481-485 e 532-535 | Miniere solo online; la conferma fallisce con "Il giocatore non e' piu' online.". | `AmmoManager` per UUID (contatori `mine_*` salvati) + coda; nel titolo `nameOf(UUID)`. |
| 2 | P2 | R-ESC | 376-402 `ask`, 607-609 `onClose` vuoto | La conferma (holder proprio) si chiude con ESC. | Usare `UserProfilePowers.confirmOver` con lo sfondo `fontTitle(..., dim)` oppure lo schema di riapertura (0.2). |

OK: R-TIT/R-BG/R-INV (`MinesGui.fontTitle` con dim), R-NAV (sempre), R-DEL (Q + conferma, 517-560), R-Y91 (pannello E208 + descrizione).

## 9. UserProfileHistory.java (2 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P2 | R-BAR (titolo) | 192 | "cronologia mute NICK" (128 px) supera `TOP_MAX` 112: `fit` taglia "CRONOLOGIA" in "CRONOLOG". | Testo più corto: "mute di NICK" / "cronologia mute" (la barra descrittiva dice già "cronologia"). |
| 2 | P3 | R-DEL (da decidere) | 421-437 | "Azzera cronologia" cancella tutte le voci con un click (con conferma). La regola dice "eliminare solo con Q". | Decidere con l'utente se i reset contano come eliminazione; se sì spostare su Q. |

OK: R-TIT (drawBg = drawPage), R-NAV (sempre), R-BAR ("cronologia"), R-DEL voce singola (Q + conferma, 459-476),
R-DATA (75), R-STATO (da/quando nelle voci), R-INV.

## 10. StaffProfileGui.java (5 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P2 | R-BG | 1254-1258 `bgPopup` | Conferma sopra le liste warn/depex/sospensioni/tutor: sotto si vede il pannello vuoto (niente barra, "N/M", icone). | Ridisegnare il popup lista completo (riusare il corpo di `popupTitle` con cursore reale). |
| 2 | P2 | R-ESC | 1726-1757 (nessun onClose) | I popup lista si chiudono con ESC. | Vedi 0.2. |
| 3 | P1 | R-STATO | 1405-1409 | Sospensione "stato: finita" con "finita il", ma senza chi l'ha tolta (se tolta a mano). | Salvare `endedBy` in `StaffDiscipline.Entry` e scrivere "&7tolta da: &f..." (o "scaduta" se è finita da sola). |
| 4 | P3 | R-LORE | 1530-1532 | Due righe "stato:" (online/offline e "già tutor"). | "&7tutor: &aattuale" per la seconda. |
| 5 | P3 | R-CHAT | 1277, 1385, 1505 | "non ancora disponibile." in chat. | Togliere o mettere solo in lore. |

OK: R-TIT (overlay corretto a -22 con avanzamento reale, topTitleDim 243), R-NAV (pagnum 1/2, 2/2; popup `popupNav` sempre),
R-BAR (`popupBar`), R-DEL (Q + conferma 1447-1450), R-DATA (141), R-INV (`INV_STAFF`), azioni offline (nessun blocco
su `getPlayer`, solo lore informativa).

## 11. StaffDiscipline.java (2 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P1 | R-STATO | 30-31 (record Entry), 437-438 `close` | Manca chi ha chiuso la sospensione (`endedBy`). | Aggiungere `UUID endedBy, String endedByName` (salvataggio 118 / lettura 78). |
| 2 | P2 | R-OFF | 491-495 `notify` | Warn/depex/sospensione a uno staffer offline: il messaggio si perde. Anche `reapply` (484-489) vale solo online (verificare che `StaffRoles` riapplichi al join). | Coda messaggi al login (come `StaffTasks`). |

## 12. ProfileLists.java (3 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P2 | R-Q | 413-427, 570-578 | Cronologia movimenti/sanzioni/acquisti: filtro senza Q = cerca (testo/nick). | Q sul filtro → incudine di ricerca, filtro temporaneo fino alla chiusura. |
| 2 | P2 | R-ESC | 600-611 | Popup chiuso da ESC. | Vedi 0.2. |
| 3 | P3 | R-FIL | 426 | Con un solo filtro la lore dice comunque "sinistro su \| destro giù". | Mostrare l'istruzione solo se `filters.size() > 1` (come TaskGui 1886). |

OK: R-TIT (popup senza titolo proprio), R-BAR (nome filtro), R-NAV, R-FIL (571-576), R-DATA (91), R-STATO (da/quando).

## 13. RoleGui.java (3 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P2 | R-NAV | 112-119 | Le frecce scorrono i gradi ma non c'è "N/M" (es. "3/11"). | Numero `(index-from+1)/(to-from)` centrato nella barra frecce (o sotto la barra del grado se la barra frecce non ha spazio: chiedere a Sara). |
| 2 | P2 | R-ESC | 248-253 | Popup chiuso da ESC. | Vedi 0.2. |
| 3 | P3 | R-TIT (rischio) | 117, 44 | Avanzamento dichiarato dopo x già spostata (vedi 0.1). | `ADV_ROLE` reale (128). |

OK: titolo della GUI di sotto (drawDimmed), R-INV, conferma, funziona offline (`StaffRoles.set` per UUID).

## 14. RulesGui.java (2 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P2 | R-TIT | 260, 569 | Il titolo cambia col filtro ("REGOLE BAN", "REGOLE WARN"...): è lo stesso comportamento che l'utente ha segnalato nei ticket. | Titolo fisso ("regole" / "regole staff"); il filtro si vede nella lore e, se il pack ha una barra, nella barra. |
| 2 | P2 | R-Q | 422, 629-635 | Q sul filtro = "crea regola", mentre la regola dice Q sul filtro = cerca per codice. | Q = cerca codice (incudine, filtro temporaneo); "crea" su un pulsante dedicato o su Q in un altro slot. |

OK: R-NAV (sempre), R-FIL (636-642), sfondo per i popup con topTitleDim e +7, R-INV (`INV_HIGH`).

## 15. RuleDetailGui.java (1 violazione)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P1 | R-DEL | 658-659 | "elimina regola": click sinistro = conferma eliminazione. | Azione solo con Q (DROP/CONTROL_DROP), lore "&cQ per eliminare", conferma invariata. |

OK: R-TIT (551/577), R-BG (drawBackground con scroll), R-INV, R-DATA, conferme specifiche.

## 16. RulePopups.java (3 violazioni)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P1 | R-BAR | 439-451 `titleOf` | Liste "cronologia CODICE" / "sanzionati CODICE" su userprofile-23: la barra del pannello resta vuota (`s.title` si scrive solo senza sfondo). | Sempre `barText(g, "cronologia" / "sanzionati")` nella barra larga (BAR_WIDE). |
| 2 | P2 | R-CHAT | 576-581 | "informazioni" manda in chat "N voci, pagina x/y, frecce per scorrere, X per tornare". | Lore con voci e pagina, niente chat. |
| 3 | P2 | R-ESC | 602-613 | Popup chiuso da ESC. | Vedi 0.2. |

OK: R-NAV (sempre), R-TIT (nessun titolo proprio con sfondo), conferma "Salvare: ...?", R-DATA (55), R-STATO (creata da/il).

## 17. TaskGui.java (1 violazione)
Riferimento per le correzioni già fatte: titolo/sfondo con "1/1" (300/425), barre, Q cerca (1886), R-DEL con Q, R-STATO (`whoWhen`), date con anno.

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P2 | R-ESC | 2355-2360 | Folder/liste popup si chiudono con ESC (le conferme no, passano da confirmOver). | Vedi 0.2. |

## 18. BlockVariantsGui.java (1 violazione)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P3 | R-ESC | (nessun onClose) | Popup varianti chiuso da ESC (chiude anche la lista). | Vedi 0.2. |

OK: titolo della lista scurito sotto il popup (`drawDimmedBackdrop`), "N/M" blocco, shopitem-2 senza barra descrittiva (immagine senza barra), inventario a colori pieni nell'immagine.

## 19. ItemsGui.java (0 violazioni)
OK: "N/M" sempre (249-255), titolo con `UpgradeManager.barTitle`. Nota P3: il titolo cambia con la categoria
("OGGETTI BLOCCHI"); le categorie sono schede, quindi lo considero accettabile; da confermare con l'utente dopo il caso ticket.

## 20. MinesGui.java (0 violazioni)
OK: `fontTitle` (stesso titolo/numero con e senza popup, R-INV, descrizione E208, "N/M" sempre).

## 21. UpgradeManager.java (0 violazioni)
OK: "N/M" sempre (579-593), nome nella barra. Nota: dipende da `upgrade-gui.title.text` (vedi UserProfileUpgrades #3).

## 22. ShopManager.java (1 violazione, fuori dalle GUI)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P3 | R-DEL | 325-329 | Shift + destro a mano vuota rimuove lo stand del negozio senza conferma. | Conferma (popup o doppia azione) prima di `removeShop`. |

Nessun inventario custom: le righe dell'ologramma passano da `KeyIcons.iconize` (648).

## 23. AdminCommand.java (1 violazione)

| # | Pri | Regola | Dove | Problema | Correzione |
|---|-----|--------|------|----------|------------|
| 1 | P2 | R-OFF | 680-699 `checkSelf` | Cerca il target con `getPlayerExact`: se il target è offline resta il nome dello staffer e il comando viene negato come "a te stesso" (per chi è sotto `self-min`). | Risolvere il target con `getOfflinePlayerIfCached` / `PlayerIds.byId` e confrontare gli UUID. |

OK: `/adminmania userprofile` e `staffprofile` aprono anche offline (403-447); comandi offline in coda (655-666).

## 24. BlockMania.java / TicketStaffGui (1 blocco)
- P1: classe `TicketStaffGui` mancante (`BlockMania.java:720`, `Tickets.java:2651-2676`): non compila e la GUI
  principale di `/adminmania ticket` non esiste. Va ripristinata (o tolti i riferimenti, usando `openStaffList`).

---

## Riepilogo violazioni per file

| File | Violazioni | di cui P1 |
|------|-----------:|----------:|
| Tickets.java | 12 | 6 |
| UserProfileGui.java | 9 | 3 |
| StaffProfileGui.java | 5 | 1 |
| UserProfilePowers.java | 4 | 1 |
| Rewards.java | 4 | 2 |
| UserProfileUpgrades.java | 3 | 2 |
| ProfileLists.java | 3 | 0 |
| RoleGui.java | 3 | 0 |
| RulePopups.java | 3 | 1 |
| UserProfileRewards.java | 2 | 2 |
| UserProfileMines.java | 2 | 1 |
| UserProfileHistory.java | 2 | 0 |
| StaffDiscipline.java | 2 | 1 |
| RulesGui.java | 2 | 0 |
| TicketDialog.java | 2 | 0 |
| RuleDetailGui.java | 1 | 1 |
| TaskGui.java | 1 | 0 |
| BlockVariantsGui.java | 1 | 0 |
| ShopManager.java | 1 | 0 |
| AdminCommand.java | 1 | 0 |
| BlockMania.java (TicketStaffGui) | 1 | 1 |
| MinesGui / ItemsGui / UpgradeManager | 0 | 0 |
| **Totale** | **64** | **22** |

## Le 10 più gravi
1. **UserProfileGui:439**: titolo normale del profilo 7 px a destra (doppio -7 nel numero pagina). Tocca 3 pagine e tutti i popup del profilo.
2. **Tickets:1372**: il titolo della lista ticket cambia col filtro ed è a colori pieni sopra un popup (deve restare "TICKET" scurito).
3. **Tickets:1371-1373**: barra descrittiva del popup lista vuota (deve dire il filtro).
4. **Tickets:1262-1275 + 1967-1985**: "1/1" mancante nella pagina principale ticket e nel popup cambia sezione (lì anche la barra è vuota).
5. **Profili offline**: UserProfileGui 905-908 + UserProfileRewards 87/112 + UserProfileUpgrades 76 + UserProfileMines 195/345 (+ Rewards API solo `Player`): metà del profilo è inutilizzabile offline.
6. **Rewards:1105-1112**: popup livelli con titolo diverso ("BATTLEPASS"...): stesso errore dei ticket.
7. **RulePopups:439-451**: liste cronologia/sanzionati delle regole con barra vuota.
8. **UserProfilePowers:197/933-951**: barra dei popup comandi che taglia le parole ("BLOCKSHEL") e i nick.
9. **Stati chiusi senza chi/quando**: Tickets 937, 1298, 1393, 1671, 1705, 2207; StaffProfileGui 1405-1409; StaffDiscipline (manca `endedBy`).
10. **RuleDetailGui:658**: eliminazione della regola con un click invece che con Q.

Fuori classifica ma bloccante: `TicketStaffGui` mancante (non compila).

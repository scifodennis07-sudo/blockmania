# PROPOSTA - STAFFBOARD (la GUI unica dello staff)

Autrice: Irene (capo team graphic design). Stato: FASE 1, solo proposta. Niente codice e niente pack finché l'utente non conferma.
Valgono sempre `docs/REGOLE_GUI.md` e `maniablock5/docs/design-team/GUIDA_STILE.md` (con PATTERN.md e RIFERIMENTI.md).
Si collega alla proposta di Davide `docs/ADMIN_TICKET_PROPOSTA.md` (nuova GUI ticket dello staff con `open(p, back)`).

---

## 0. Cosa c'è già nel plugin (base della staffboard)

| Cosa | Dove (classe / comando) | Chi può (da `config.yml adminmania.actions` e dal codice) |
|---|---|---|
| Ticket dello staff | `Tickets`, `/adminmania ticket [aperti\|miei\|chiusi\|numero]` | tutto lo staff (anche jr-helper). Massimo 9 ticket presi a testa |
| Task e ordini | `TaskGui` / `StaffTasks`, `/adminmania task` | aprire: tutti. Creare: helper+. Prendere un ordine: mod+ (custom: sr-mod+). Eliminare: chi l'ha creata o admin+. Ripristinare: admin+ |
| Profilo player | `UserProfileGui` (+ Mines, Upgrades, Rewards, Powers, History), `/adminmania userprofile <nome\|u-id\|s-id>` | vedere: helper+. Dare: jr-admin+, togliere: sr-mod+, con tetti per volta e al giorno. Funziona anche offline |
| Sanzioni | `SanctionManager` (mute / warn / ban / blacklist, auto-ban al 4° warn) | mute e warn: helper+. Ban: mod+. Togliere o ridurre: sr-mod+. Oggi `sanctions.enforce: false` (modalità TEST) |
| Regolamento player e staff | `RulesGui` / `RuleDetailGui` / `Rules`, `/adminmania rules` e `staffrules` | vedere: helper+. Importare: admin+. Modificare: oggi solo founder/OP (`sanctions.edit` non è nel config) |
| Profilo staff | `StaffProfileGui` (2 pagine: stelle, carriera, conteggi; grado, depex, warn staff, sospendi, tutor, promuovi) | vedere: helper+. Azioni con i permessi `sanctions.*` e solo su chi ha un grado più basso |
| Gradi | `StaffRoles` + `RoleGui` (pexa) | scala jr_helper > helper > jr_mod > mod > sr_mod > jr_admin > admin > sr_admin (+ co-founder, founder, OP). Speciali: builder, dev, t_maker |
| Disciplina staff | `StaffDiscipline` (warn, depex, sospensione, tutor) | chi è sospeso perde tutti i permessi di ruolo finché la sospensione non scade |
| Statistiche staff | `StaffStats` (ban / warn / mute / blacklist / ticket / assistenze vocali, stelle 7g / 30g / sempre, carriera) | le assistenze vocali esistono come voce ma oggi non vengono registrate |
| Cronologia | `MovementLog` (miniere, acquisti, ticket, ricompense, potenziamenti, sanzioni, oggetti, staff) e `adminmania.log` (ogni comando staff, anche i NEGATI) | |
| Strumenti /adminmania | gruppi economy, profile, items, build, shops, system | items list: jr-mod+. System see: jr-mod+. Limits: admin+. Shops: admin+ o builder. Build: sr-admin+ o builder. Reload: founder o dev. Debug e tutorial: founder, dev o texturemaker |
| Avvisi di oggi | solo chat: "nuovo ticket #12", "nuovo messaggio nel ticket", "ti ha taggato"; bossbar dell'incarico task (5 minuti) | il pallino giallo `ticket/badge.png` (9x9, `#ffcd28`) c'è già nella GUI ticket del player per i messaggi non letti |

Cose da tenere a mente:
- Il jr-helper oggi apre solo ticket e task: profili, sanzioni e regole partono da helper.
- Un sospeso ha livello 0, quindi oggi non apre niente dello staff.
- Le GUI che si aprono dalla staffboard devono avere la X che diventa "indietro" (lo stesso `back` della proposta di Davide), e tornare sulla pagina della staffboard da cui sono partite.

---

## 1. Come si apre

- `/adminmania staffboard` (alias proposti: `/staffboard` e `/sb`). Tutti i comandi di oggi restano e continuano a funzionare.
- Quando uno staffer entra nel server: un messaggio in chat col font tiny, solo se ha notifiche, cliccabile: "HAI 4 NOTIFICHE NELLA STAFFBOARD." (si può spegnere in OPZIONI).
- Facoltativo, da decidere: un oggetto fisso in hotbar (pattern 35 di PATTERN.md) che apre la staffboard. Io non lo metterei: lo staff usa già tanti comandi.

---

## 2. Le sezioni

I nomi in MAIUSCOLO sono le etichette dei pulsanti. Le ho scelte corte apposta (vedi la sezione 5 sulle larghezze).

| # | Sezione | Chi la vede | Cosa fa | Cosa apre |
|---|---|---|---|---|
| 1 | **TICKET** | tutto lo staff | ticket aperti, i miei, chiusi, valutazioni, resoconto | la nuova GUI ticket staff di Davide (`TicketStaffGui.open(p, back)`) |
| 2 | **TASK** | tutto lo staff (crea da helper, prende da mod) | lista, crea, cestino, completate, resoconto, e il mio incarico in corso | `TaskGui.open(p, back)` (il `back` va aggiunto) |
| 3 | **PLAYER** | helper+ | player online, cerca per nick o id (u-00012, s-3), ultimi profili aperti | popup lista con le teste, poi `UserProfileGui` (anche offline) |
| 4 | **SANZIONI** | helper+ | sanzioni attive (mute, ban, blacklist), player a 3 warn, in scadenza, fatte da me, cronologia di oggi | popup lista; il click apre il profilo del player sulla parte sanzioni |
| 5 | **NOTIFICHE** | tutto lo staff | tutte le notifiche gialle in un posto solo (sezione 3) | popup lista notifiche |
| 6 | **STAFF** | helper+ | elenco staffer: online, per grado, sospesi, i miei allievi (tutor), ultimi pexati | popup lista teste, poi `StaffProfileGui` |
| 7 | **PROFILO** | tutto lo staff | il MIO profilo staff: stelle, carriera, conteggi, grado, warn staff ricevuti, sospensione | `StaffProfileGui` su me stesso |
| 8 | **REGOLE** | helper+ | sinistro: regolamento player; destro: regolamento staff | `RulesGui` / `StaffRulesGui` |
| 9 | **RESOCONTO** | tutto lo staff (vede le sue azioni); admin+ vede tutti | registro unico: `adminmania.log`, `MovementLog` STAFF, eventi task e ticket. Filtri: tutto, comandi, sanzioni, ticket, task, negati | popup lista |
| 10 | **OPZIONI** | tutto lo staff | impostazioni personali delle notifiche: avviso in chat all'ingresso, suono, quali sezioni notificare | popup con interruttori (pattern 22) |
| 11 | **OGGETTI** | jr-mod+ (dare oggetti e assegni: sr-admin+) | lista oggetti, blocchi e varianti | `ItemsGui` / `BlockVariantsGui` |
| 12 | **MINIERE** | sr-admin+ o builder | creare e gestire le miniere | GUI e comandi `build mines` di oggi |
| 13 | **NEGOZI** | admin+ o builder | negozi e munizioni | comandi `shops` di oggi |
| 14 | **SISTEMA** | jr-mod+ vede; limitazioni admin+; reload founder o dev; tutorial e test grafici founder, dev o texturemaker | stato del server (modalità test sanzioni e task, player online), limitazioni, reload, tutorial | ogni pulsante esegue il comando che c'è già, con la sua conferma |
| 15 | **ANDAMENTO** | admin+ | numeri dello staff e del server: ticket aperti e chiusi, voto medio, sanzioni, task completate (oggi / 7 giorni / 30 giorni), classifica staff | popup lista |

Regole che valgono per tutte le sezioni:
- Una sezione che il grado non può usare **non mostra testo** del tipo "solo mod" (REGOLE_GUI la vieta). Proposta: il pulsante resta disegnato ma spento, coperto dal velo scuro (GUIDA_STILE 4.3), con il solo nome in grigio nella lore. In alternativa si nasconde del tutto (domanda all'utente).
- Le pagine che uno staffer non può usare per niente non ci sono proprio: un helper vede "1/2", un admin "1/3".
- Lo staffer sospeso apre una staffboard ridotta: solo PROFILO (con la fine della sospensione) e REGOLE staff (domanda all'utente).
- Ogni azione passa dai controlli di sempre (`AdminCommand.authorize`, tetti, azioni su se stessi, incarichi delle task). La staffboard non aggiunge permessi: apre solo le GUI che ci sono già.

---

## 3. Le notifiche gialle

### 3.1 Due tipi di notifica
- **STATO**: è un conteggio calcolato dal vivo, che sparisce da solo quando il problema è risolto. Esempio: "3 ticket da prendere" torna a zero quando qualcuno li prende.
- **EVENTO**: è una cosa successa a te, che resta finché non la guardi. Esempio: "Luca ti ha taggato nel ticket #12". Si salva per staffer, quindi la trovi anche se eri offline.

### 3.2 Quali eventi le generano

| Sezione | Notifica | Tipo | Chi la riceve | Click sulla notifica |
|---|---|---|---|---|
| TICKET | ticket nuovo senza staff (da prendere) | stato | tutto lo staff | apre la chat del ticket |
| TICKET | ticket senza risposta da più di X minuti (proposta: 10) | stato | tutto lo staff; dopo 30 minuti anche mod+ in cima alla lista | apre la chat |
| TICKET | messaggi non letti in un ticket che ho preso | stato | io | apre la chat sul messaggio |
| TICKET | mi hanno taggato in un ticket | evento | io | apre la chat |
| TICKET | segnalazione di un player (sezione "report") nuova | stato | helper+ | apre la chat |
| TICKET | chiusura automatica entro 1 ora in un mio ticket | stato | io | apre la chat |
| TICKET | ho ricevuto una valutazione da 1 o 2 stelle | evento | io (admin+: tutte) | apre il ticket chiuso |
| TASK | ordini che il mio grado può prendere | stato | mod+ (custom: sr-mod+); non conta le task create da me | apre la lista task sul filtro "prendibili" |
| TASK | ho un incarico in corso (con i minuti che mancano) | stato | io | riapre l'ordine in carico |
| TASK | il mio incarico è scaduto ed è stato annullato | evento | io | apre il resoconto della task |
| TASK | un ordine di una task creata da me è stato completato / la task è finita | evento | chi l'ha creata | apre la cartella della task |
| SANZIONI | è entrato un player con un mute attivo | stato (finché è online) | helper+ | apre il profilo |
| SANZIONI | è rientrato un player appena sbannato o col ban scaduto (ultime 24 ore) | evento | helper+ | apre il profilo |
| SANZIONI | un player è a 3 warn (al prossimo scatta il ban) | stato | helper+ | apre il profilo |
| SANZIONI | è scattato il ban automatico per 4 warn | evento | mod+ | apre la cronologia sanzioni |
| SANZIONI | una mia sanzione è stata tolta o ridotta da un altro | evento | io | apre la voce nella cronologia |
| STAFF | ho ricevuto un warn staff, un depex o una sospensione | evento | io | apre il mio profilo staff, pagina 2 |
| STAFF | mi hanno assegnato come tutor di qualcuno / ho un nuovo tutor | evento | i due interessati | apre il profilo staff dell'altro |
| STAFF | una sospensione staff finisce entro 24 ore | stato | admin+ | apre il profilo staff |
| STAFF | nuovo staffer pexato, depex, promozione | evento | admin+ | apre il profilo staff |
| PLAYER | una modifica fatta a un player offline è stata applicata al suo login | evento | chi l'ha fatta | apre il profilo |
| RICHIESTE (nuovo, facoltativo) | uno staffer ha chiesto un'azione che il suo grado non può fare ("chiedilo a un ruolo più alto") | stato | i gradi che la possono fare | apre la richiesta: accetta (verde) / rifiuta (rosso), con conferma |
| SISTEMA | le sanzioni sono in modalità TEST (`sanctions.enforce: false`) | stato fisso | admin+ | apre SISTEMA |

Le "richieste" oggi non esistono: sarebbe una funzione nuova. La metto qui perché nasce proprio dai messaggi NEGATO che il plugin scrive già. Decide l'utente se farla.

### 3.3 Come si vedono
- **Badge giallo sul pulsante.** È il pallino 9x9 che c'è già (`ticket/badge.png`, `#ffcd28`, contorno `#6e3c00`), messo nell'angolo in alto a destra del riquadro, sopra il bordo, come la notifica dei ticket del player. Dentro c'è il numero, da 1 a 9, oppure "9+", scritto con cifre 3x5 nel marrone scuro `#5a2800`. Senza notifiche il badge non c'è.
- **Un colore solo: giallo**, che vuol dire "da guardare". Il rosso nel nostro stile vuol dire chiudi o annulla, quindi non lo uso per le urgenze: quelle si riconoscono perché stanno prime nella lista e hanno un "!" nell'icona.
- **Puntino giallo sulle frecce** della barra in alto (5x5) quando c'è una notifica in un'altra pagina della staffboard.
- **Lista NOTIFICHE**: popup lista (lo stesso U+E119 di task e ticket), con le più recenti e le urgenti prima. Ogni voce ha l'icona della sezione con un piccolo "!" giallo in basso a destra (badge modificatore, GUIDA_STILE 9.1). Lore compatta:
  ```
  Ticket #12 - report                (nome)
  player: Mario
  quando: 24/11/2026 14:52
  sezione: ticket
                                     (riga vuota)
  [sinistro] vai  [destro] letta  [Q] togli
  ```
- Filtri della lista (sinistro su, destro giù, Q cerca): tutte, non lette, ticket, task, sanzioni, staff, richieste.
- **Fuori dalla GUI** restano i messaggi in chat di oggi (nuovo ticket, nuovo messaggio, tag), più l'avviso all'ingresso. Tutto si può spegnere in OPZIONI.

### 3.4 Cosa succede cliccando
- Sul **pulsante di una sezione**: sinistro apre la sezione normale. Destro apre la sezione già sul filtro dove c'è la notifica (per esempio ticket "da prendere"). Q, dove serve, cerca (incudine: #id, codice, nick, u-id, s-id), come vuole REGOLE_GUI per i filtri.
- Su **una notifica** della lista: sinistro porta dove c'è il problema (la X di quella GUI torna alla lista notifiche). Destro la segna come letta. Q la toglie dalla lista, sempre con conferma, perché è un'eliminazione.
- Sul **pulsante NOTIFICHE**: sinistro apre la lista. Destro apre solo le non lette. Q segna tutto come letto, con conferma.
- Le notifiche di tipo STATO non si possono segnare come lette: spariscono da sole quando il problema è risolto. Così nessuno può "spegnere" un ticket senza risposta.

---

## 4. Tre concept di layout

Tutti e tre seguono lo stile di Dennis: 9x6, sfondo disegnato nel titolo, barra in alto con X / frecce con "N/M" / "i", inventario in basso copiato da `task.png`, popup sopra con la staffboard scurita e il titolo "STAFFBOARD" identico, solo più scuro.

Fila in alto, uguale nei tre concept:
- **X** (slot 0-1): chiude. Nelle GUI aperte dalla staffboard la X diventa "indietro".
- **frecce** (2 e 6) e **barra** (3-5): pagina precedente / successiva, con "N/M" sempre al centro. La barra non ha azioni; la lore dice la pagina e quante notifiche ci sono in tutto.
- **i** (7-8): informazioni, senza click. Lore: grado, id staffer, ticket presi x/9, incarico task, notifiche totali, sospensione se c'è.

### CONCEPT A - "COME TASK, A PAGINE" (quello che consiglio)

Lo stesso schema di `task.png`: 3 card grandi con icona ed etichetta, più 2 pulsanti larghi in basso. Le sezioni si dividono in 3 pagine e si cambiano con le frecce.

```
       c0   c1   c2   c3   c4   c5   c6   c7   c8
r0   [  X   X ][ <  |    1/3     |  > ][  i    i ]
r1   [               *][               *][               *]
r2   [  CARD 1        ][  CARD 2        ][  CARD 3        ]
r3   [ [ETICHETTA]    ][ [ETICHETTA]    ][ [ETICHETTA]    ]
r4   [ PULSANTE BASSO 1   *]  ..  [ PULSANTE BASSO 2   *]
r5   [ (36-39, 45-48)      ]  ..  [ (41-44, 50-53)      ]
                                  (* = badge giallo)
```
Slot come TaskGui: card 1 = 9-11/18-20/27-29, card 2 = 12-14/21-23/30-32, card 3 = 15-17/24-26/33-35, basso sinistra = 36-39 e 45-48, basso destra = 41-44 e 50-53 (40 e 49 sono lo spazio in mezzo).

| Pagina | Card 1 | Card 2 | Card 3 | Basso sinistra | Basso destra |
|---|---|---|---|---|---|
| 1/3 LAVORO (tutti) | TICKET | TASK | PLAYER | SANZIONI | NOTIFICHE |
| 2/3 STAFF (tutti) | STAFF | PROFILO | REGOLE | RESOCONTO | OPZIONI |
| 3/3 ADMIN (solo chi ha almeno uno strumento) | OGGETTI | MINIERE | SISTEMA | NEGOZI | ANDAMENTO |

Pulsanti:

| Pulsante | Sinistro | Destro | Q |
|---|---|---|---|
| TICKET | schermata TICKET staff | lista "da prendere" oppure "non letti" (dove c'è la notifica) | cerca #id, codice o nick e apre la chat |
| TASK | schermata TASK | se ho un incarico lo riapre, altrimenti la lista "prendibili da me" | cerca #task |
| PLAYER | lista player online (filtri: tutti, nuovi oggi, sanzionati online, staff online) | ultimi profili aperti da me | cerca nick o u-id e apre il profilo (anche offline) |
| SANZIONI | sanzioni attive (filtri: mute, ban, blacklist, 3 warn, in scadenza, fatte da me) | cronologia di oggi | cerca nick |
| NOTIFICHE | lista notifiche | solo le non lette | segna tutto come letto (con conferma) |
| STAFF | lista staffer (filtri: online, per grado, sospesi, i miei allievi) | il mio profilo staff | cerca nick o s-id |
| PROFILO | il mio profilo staff, pagina 1 | pagina 2 (grado e disciplina) | - |
| REGOLE | regolamento player | regolamento staff | cerca il codice della regola |
| RESOCONTO | le mie azioni | (admin+) le azioni di tutto lo staff | cerca nick o codice |
| OPZIONI | popup interruttori delle notifiche | - | - |
| OGGETTI / MINIERE / NEGOZI / SISTEMA | apre la GUI o il popup comandi della sezione | - | - |
| ANDAMENTO | numeri di oggi / 7 / 30 giorni | classifica staff | - |

Pro: è identico a TASK, che all'utente piace; un solo sfondo per tutte e 3 le pagine (icone ed etichette sono sovrapposte); le pagine si adattano al grado.
Contro: le notifiche si vedono solo come badge; bisogna sfogliare per arrivare a STAFF e REGOLE.

### CONCEPT B - "HUB CON LE NOTIFICHE SEMPRE IN VISTA"

Quattro porte grandi (3x2 slot) a sinistra e una colonna di notifiche dal vivo a destra. Le frecce in alto scorrono le pagine delle notifiche, non della staffboard.

```
       c0   c1   c2   c3   c4   c5   c6   c7   c8
r0   [  X   X ][ <  |    1/4     |  > ][  i    i ]
r1   [ ICONA         *][ ICONA         *][ n1 ][ n2 ][ n3 ]
r2   [ TICKET         ][ TASK           ][ n4 ][ n5 ][ n6 ]
r3   [ ICONA         *][ ICONA         *][ n7 ][ n8 ][ n9 ]
r4   [ PLAYER         ][ SANZIONI       ][ n10][ n11][ n12]
r5   [ STAFF         *][ REGOLE        *][ ALTRO         *]
```
- Porte: TICKET 9-11/18-20, TASK 12-14/21-23, PLAYER 27-29/36-38, SANZIONI 30-32/39-41. Icona in alto al centro, etichetta sotto, badge nell'angolo.
- Notifiche: slot 15-17, 24-26, 33-35, 42-44 (12 slot vanilla con item veri: pattern "lista a griglia"). Le 12 più recenti e urgenti. Sinistro vai, destro letta, Q togli (con conferma).
- Le frecce scorrono le notifiche ("1/4" sono le pagine delle notifiche). Con poche notifiche si vede "1/1".
- Fila in basso: STAFF (45-47), REGOLE (48-50), ALTRO (51-53).
- ALTRO apre un popup griglia (7 colonne) con PROFILO, RESOCONTO, OPZIONI e, per chi può, OGGETTI, MINIERE, NEGOZI, SISTEMA, ANDAMENTO. Il badge di ALTRO somma le notifiche di quelle sezioni.
- Click su TICKET, TASK, PLAYER, SANZIONI, STAFF e REGOLE come nella tabella del concept A.

Pro: appena apri vedi cosa c'è da fare, senza un altro click; tutte le sezioni principali stanno in una pagina sola.
Contro: è uno sfondo nuovo (non è più `task.png`); le porte sono più basse delle card di TASK; le sezioni secondarie finiscono in un popup.

### CONCEPT C - "LA MIA SCHEDA + RIGHE" (elemento principale a sinistra, come il profilo)

A sinistra la scheda dello staffer (testa nel riquadro hatch e 3 celle con i numeri). A destra 5 righe larghe, ognuna con icona, nome e una cella numero che diventa gialla. Le frecce cambiano il gruppo di righe.

```
       c0   c1   c2   c3   c4   c5   c6   c7   c8
r0   [  X   X ][ <  |    1/3     |  > ][  i    i ]
r1   [ TESTA    ][ic][ TICKET                 ][ 9+ ]
r2   [ (hatch)  ][ic][ TASK                   ][  2 ]
r3   [ST  4,6   ][ic][ PLAYER                 ][    ]
r4   [OR  3:12  ][ic][ SANZIONI               ][  1 ]
r5   [TK  3/9   ][ic][ NOTIFICHE              ][  5 ]
     (ST = stella, OR = orologio, TK = ticket: icone; le celle
      numero a destra diventano gialle quando c'e' una notifica)
```
- Testa (9-10/18-19): sinistro = il mio profilo staff, destro = pagina 2.
- Celle di sinistra (27-28, 36-37, 45-46): stelle degli ultimi 7 giorni, incarico task con il tempo che manca (sinistro lo riapre), ticket presi x/9 (sinistro apre i miei ticket). Solo icona più numero, scritto dal plugin.
- Righe (colonne 2-8): icona nello slot 2, nome nella barra 3-7, cella numero nello slot 8. Con notifiche la cella è gialla e c'è il numero; senza è grigia e vuota. Tutta la riga è un unico pulsante, con lo stesso sinistro / destro / Q del concept A.
- Pagina 1: TICKET, TASK, PLAYER, SANZIONI, NOTIFICHE. Pagina 2: STAFF, REGOLE, RESOCONTO, OPZIONI, ANDAMENTO (admin+). Pagina 3 (solo chi può): OGGETTI, MINIERE, NEGOZI, SISTEMA.

Pro: è la più informativa (vedi subito il tuo carico di lavoro); le righe con la cella numero sono nel linguaggio di `ricompense.png`; ci sono 5 sezioni per pagina.
Contro: i pulsanti sono larghi ma alti una riga sola, quindi meno "grandi" di quelli di TASK; serve uno sfondo nuovo.

---

## 5. Cosa bisogna disegnare (Sara e il team, dopo la conferma)

Tutto in `assets/minecraft/textures/font/custom-gui/staffboard/`, con il nome file minuscolo e la voce in `README_CARTELLE.txt`. Ogni nuovo carattere va nel font `minecraft:task` (per esempio U+E182 e successivi: Davide usa già U+E181), così vanno bene `inv_full` U+E000, il velo dei popup e i centri di TaskGui.

**Sfondo (solo quello del concept scelto)**
- A: `staffboard.png`, copia di `task.png` con le strisce delle etichette vuote (le scritte le mette il plugin, come COMPLETATE e RESOCONTO) e le card senza icona. Basta UN solo PNG per tutte e 3 le pagine.
- B: `staffboard.png` nuovo: 4 porte 3x2, griglia 3x4 di slot vanilla, 3 pulsanti in basso, e il popup griglia di ALTRO (si riusa il popup lista a 7 colonne).
- C: `staffboard.png` nuovo: riquadro hatch 2x2, 3 celle piatte 2x1, 5 righe (slot icona + barra bicolore + cella numero).
- Velo "spento" per ogni pulsante (A: 5, B: 7, C: 5 righe), con i colori già scuriti (x0,61 + 26), per le sezioni che il grado non può usare.

**Badge**
- Si riusa `ticket/badge.png` 9x9. In più servono le cifre 3x5 in `#5a2800` (1-9 e "9+": 10 glifi) e il puntino 5x5 per le frecce.
- Solo C: la cella numero "accesa" in giallo (`#ffcd28` con il bordo scuro alto/sinistro e chiaro basso/destro).
- Da segnalare a Dennis: nella guida il giallo avviso ha il contorno `#69410a`, mentre `badge.png` usa `#6e3c00`. Bisogna sceglierne uno.

**Icone (contorno tinto `#221628`, luce in alto a sinistra, ridisegnate alla misura giusta e mai scalate)**
- A: icone delle card alla misura delle cartelle di TASK (circa 22x28): TICKET, TASK, PLAYER (testa con lente), STAFF (scudo), PROFILO (testa con stella), REGOLE (libro), OGGETTI, MINIERE (piccone), SISTEMA (chiave inglese). Icone 12x15 dei pulsanti bassi: SANZIONI (martello), NOTIFICHE (campanella), RESOCONTO (si riusa quella di TASK), OPZIONI (ingranaggio), NEGOZI (bancarella), ANDAMENTO (grafico).
- B: le stesse, alla misura delle porte (16x16 o 22x22, da misurare sul PNG).
- C: tutte a 16x16, più le 3 mini icone delle celle (stella, orologio, ticket).
- Per tutti: icone item 16x16 per la lista NOTIFICHE, una per sezione, con il badge "!" giallo in basso a destra. Le icone di `icone-profilo/` (ticket, staff, sanction, warn, mute, ban) vanno ridisegnate, non rimpicciolite.

**Etichette.** Le scrive il plugin col font tiny. Spazio utile: card di TASK circa 40 px, pulsante basso 59 px, porta del concept B 52 px.
- SANZIONI (43 px) non ci sta in una card: per questo nel concept A è in basso. STATISTICHE e IMPOSTAZIONI non ci stanno nemmeno nei pulsanti larghi, quindi diventano ANDAMENTO e OPZIONI.
- Quando la scritta ha larghezza dispari in uno spazio pari, la centratura va controllata al pixel.

**Si riusa così com'è.** Popup lista U+E119, conferma `confirmOver`, incudine per la ricerca, popup frecce U+E11F, e tutte le GUI di destinazione (ticket, task, profili, regole).

**Nota tecnica, per il programmatore e non per il disegno.** Il badge è nel titolo, quindi per aggiornarlo bisogna riaprire l'inventario, come già fanno le tacche dello userprofile. Le lore invece si aggiornano dal vivo. Proposta: ridisegnare il titolo solo quando un conteggio cambia, e al massimo una volta ogni 5 secondi.

---

## 6. Riassunto per l'utente

Staffboard = una GUI (`/adminmania staffboard`, alias `/sb`) che apre tutto: TICKET, TASK, PLAYER, SANZIONI, NOTIFICHE, STAFF, PROFILO, REGOLE, RESOCONTO, OPZIONI e, per chi può, OGGETTI, MINIERE, NEGOZI, SISTEMA, ANDAMENTO. I permessi restano quelli di oggi.
Notifiche: badge giallo con numero (1-9, "9+") sul pulsante, puntino sulle frecce, lista NOTIFICHE. Sinistro = vai, destro = letta, Q = togli con conferma.
Consiglio il concept A (identico a TASK, 3 pagine, un solo sfondo). B tiene le notifiche sempre in vista; C mette la tua scheda a sinistra e le righe a destra.
Da decidere:
1. Quale concept: A, B o C?
2. Le sezioni che il grado non può usare: spente col velo scuro, o nascoste?
3. Va bene la lista delle sezioni? Ne togli o ne aggiungi qualcuna?
4. Vuoi le RICHIESTE (chiedere un'azione a un grado più alto)? È una funzione nuova.
5. Dopo quanti minuti un ticket "senza risposta" diventa notifica (io propongo 10)?
6. Lo staffer sospeso vede una staffboard ridotta (PROFILO e REGOLE staff) o niente?
7. Avviso in chat all'ingresso e oggetto in hotbar: sì o no?

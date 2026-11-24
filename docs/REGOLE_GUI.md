# Regole GUI BlockMania (valgono in TUTTO il plugin, sempre)

Ogni modifica chiesta dall'utente su una GUI va applicata a TUTTE le GUI e a tutti i popup del plugin
(profilo utente, staffprofile, task, ticket, /adminmania ticket, regole, miniere, ricompense, potenziamenti,
oggetti, varianti, shop, cronologia, liste...). Mai solo nella GUI dello screenshot.

## Popup
- Sotto un popup la GUI di sotto e' scurita, ma il TITOLO in alto resta IDENTICO (stesso testo, stessa posizione
  al pixel), solo piu' scuro. Il popup non scrive un suo titolo. Popup sopra popup: resta il titolo della GUI principale.
- Il titolo con popup aperto e chiuso deve stare nella stessa posizione esatta (stesso x, stesso font).
- L'inventario del player NON si scurisce (inv_full U+E000/E001/E002).
- Lo sfondo scurito combacia al pixel con la GUI normale.
- La barra descrittiva del popup mostra SEMPRE un testo (nome filtro/sezione); il testo non esce mai dalla barra
  (barra allargata, font piu' piccolo o testo accorciato).
- Ogni popup ha la sua descrizione specifica dove serve; punteggiatura corretta.
- Popup si chiudono solo con i pulsanti (conferma/annulla ecc.).
- Scritte dei pulsanti verde/rosso: font tiny_y91.

## Navigazione e filtri
- Numero pagina "N/M" sempre presente (anche 1/1), centrato nella barra frecce.
- Filtri: click sinistro = su (precedente), click destro = giu' (successivo). Azioni extra su Q.
- Q sul filtro = filtro avanzato (cerca per codice/nick) dove ha senso; sparisce alla chiusura della GUI.
- Eliminare: SOLO con Q, sempre con conferma. Mai altri tasti per eliminare.

## Testi e lore
- Lore semplici e compatte: "&7etichetta: &fvalore", riga vuota, istruzioni in fondo su UNA riga.
- Icone dei tasti (KeyIcons) al posto delle parole click/sinistro/destro/shift/Q ovunque (lore, nomi, chat,
  bossbar, actionbar, titoli).
- Date sempre con anno: dd/MM/yyyy HH:mm.
- Stati "completato/chiuso/risolto": dire anche DA CHI e QUANDO (data con anno e ora).
- Riquadri di messaggio centrati: testo centrato.
- Niente istruzioni in chat inutili, niente righe tipo "solo srmod".

## Profili
- Nei profili (utente e staff) si puo' fare TUTTO anche se il player e' OFFLINE (dati salvati/coda offline).

## Grafica
- Stile dell'utente (docs/design-team/GUIDA_STILE.md nel repo pack), icone ridisegnate non scalate,
  nomi file minuscoli, allineamenti misurati al pixel.

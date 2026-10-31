# Test della 1.0.0-beta.5 multi-versione

Cosa resta da verificare prima di considerare affidabili i jar per 1.21–1.21.10.
Già fatto il 2026-10-27: build e test unitari di tutti e 6 i nodi; mixin controllati sul bytecode di
1.21.1; server dev 1.21.1 con lo smoke test (passa tutto). Su 1.21.11 valgono i test delle beta precedenti.

Ordine consigliato: parte 1 (automatica, ~3 min per versione), poi 2, poi 3. Le parti 4 e 5 sono veloci.
Segna l'esito nella tabella in fondo.

---

## 1. Smoke test automatico sui server dev

Per ogni nodo non ancora provato:

1. Leggi e accetta l'EULA di Minecraft scrivendo `eula=true` in `versions/<nodo>/run/eula.txt`
   (cartella da creare). Lo script non lo fa da solo: lo deve fare una persona.
2. Lancia lo script:

```bash
tools/smoke-test.sh 1.21.2
```

Nodi da fare: `1.21.2`, `1.21.5`, `1.21.6`, `1.21.9`. Poi `1.21.11` come regressione, perché il codice
1.21.11 è stato spostato in `MinecraftCompat` e quel server non è più stato riavviato.

Lo script avvia il server, fa force-load di un chunk, evoca zombie/villager/mucca/item/orb di XP, piazza
una fornace e lancia `status`, `profile start 5`, `top`, `config reload`, `tuning status`, una regola per
dimensione (aggiunta e poi tolta), `spread`. Poi controlla il log:

- il jar giusto è caricato e TickEngine si inizializza;
- il throttling delle block entity salta tick (mixin `LevelMixin` e `BoundTickingBlockEntityMixin`);
- le entità ricevono tick minimi (`ServerLevelMixin`, accessor di item, orb, `noActionTime`, portal cooldown);
- il profiler misura entità e block entity, e il report HTML viene scritto;
- il config `.json5` si ricarica (parsing lenient di Gson: su 1.21.1/1.21.2 è il ramo `setLenient`);
- regole, nomi `Identifier`/`ResourceLocation` e regola per dimensione (`identifier()`/`location()`);
- nessun errore di mixin, nessun `NoSuchMethodError`/`NoSuchFieldError`/`NoClassDefFoundError`,
  nessuna eccezione nel tick, nessun warning di TickEngine, nessun comando rifiutato.

Esito: `PASS`/`FAIL` per ogni controllo, log in `build/smoke/<nodo>/server.log`.
Se un controllo fallisce, guarda prima il log: cerca `Mixin`, `TickEngine`, `Exception`.

Dalla beta.6 lo script prova anche la modalità emergenza avviata a mano (l'auto-tuning sale al massimo e
torna a 0) e lo stacking (tre mucche diventano "Cow x3", `/damage` ne toglie una, `/kill` toglie il resto).

Lo script **non** copre: l'AI saltata (serve un giocatore vicino), i click in chat, i permessi da
giocatore. Sono la parte 3.

## 2. Estremi dei range con i jar di release su un server Fabric vero

Ogni jar è compilato sulla versione più bassa del suo range. Il server dev prova quella. Le versioni
più alte del range invece non sono mai state avviate: lì ho controllato solo che i simboli esistano.
Questa è la parte più importante dopo la 1.

Server da preparare (installer server da https://fabricmc.net/use/server/, Loader 0.19.5 o più nuovo,
Fabric API della stessa versione di Minecraft da Modrinth). Usa i jar in `build/release/1.0.0-beta.5/`:

| Minecraft del server | Jar da usare | Perché |
|---|---|---|
| **1.21.4** | `+1.21.2` | Priorità alta. 1.21.4 ha una Gson diversa da 1.21.2; il jar non deve usare la nuova |
| **1.21.8** | `+1.21.6` | Priorità alta. Fine del range, mai avviato |
| 1.21.10 | `+1.21.9` | Fine del range |
| 1.21 | `+1.21.1` | Inizio del range (la 1.21.1 è un hotfix, rischio basso) |
| 1.21.3, 1.21.7 | `+1.21.2`, `+1.21.6` | Facoltative: versioni intermedie mai controllate, nemmeno i simboli |

Su ognuno, dalla console del server, gli stessi comandi dello script:

```
forceload add 0 0
summon minecraft:zombie 8 80 8
summon minecraft:villager 9 80 9
setblock 5 80 5 minecraft:furnace
tickengine status
tickengine profile start 5
tickengine config reload
tickengine rules entities list
```

Atteso: il server parte senza errori di mixin; `status` mostra "skipped N of 20 ticks" con N > 0 e
"minimal" > 0; il profilo elenca villager e zombie e salva l'HTML; `config reload` risponde
"TickEngine config reloaded". Nel `logs/latest.log` nessuna riga `ERROR`/`WARN` di TickEngine o Mixin.

## 3. Test con un giocatore connesso

Serve un client della stessa versione (in dev: `./gradlew :<nodo>:runClient`, poi connettersi a
`localhost`). Da fare almeno su **1.21.1** (vecchia API della chat e permessi `int`), **1.21.4** (vecchia
chat, estremo del range), **1.21.6** e **1.21.9** (librerie dei permessi diverse), **1.21.11**.

**a. AI saltata (`MobMixin`).** Con i default (piena velocità entro 2 chunk, AI rallentata fino a 4) evoca
un villager e uno zombie a circa 3 chunk da te (~50 blocchi), poi `/tickengine status`.
Atteso: "without AI" > 0; i mob si muovono ancora ma decidono meno spesso. Su 1.21.1 il punto di
iniezione è diverso (`customServerAiStep()` senza argomenti), quindi è il test più importante.
Torna vicino: devono tornare normali entro pochi secondi.

**b. Click in chat (`ClickEvent`/`HoverEvent`, diversi prima di 1.21.5).**
- `/tickengine config list`: passando il mouse su una riga compare la spiegazione; cliccando, la chat
  si riempie con `/tickengine config set <chiave> `.
- `/tickengine spread disable` e poi `/tickengine spread enable`: il comando di conferma sottolineato,
  cliccato, finisce nella chat.
- `/tickengine profile start 10`, poi `/tickengine top entities`: un nome sottolineato, cliccato, ti
  teletrasporta sull'entità più lenta e il tooltip mostra dimensione e coordinate.

**c. Teletrasporto vicino al tetto del mondo (`maxY`, diverso su 1.21.1).** Su 1.21.1 piazza una fornace
accesa a y = 318, fai un profilo e clicca la fornace in `/tickengine top blockentities`. Atteso: atterri
sopra la fornace, non dentro un blocco e non oltre il limite.

**d. Permessi senza LuckPerms.** Un giocatore non op non vede `/tickengine` (o riceve un errore).
`/op` lo porta al livello 4: i comandi funzionano. Con `permissions.commandOpLevel` a 4 e un giocatore
di livello 3 (`op-permission-level=3` in `server.properties`) i comandi devono essere rifiutati.

**e. Permessi con LuckPerms.** Su 1.21.1 e 1.21.9 almeno (TickEngine include due build diverse della
libreria; LuckPerms porta la sua e Fabric usa la più nuova). Giocatore non op:
`/lp user <nome> permission set tickengine.status true` permette `/tickengine status` ma non
`/tickengine config`; con `tickengine.notify` il giocatore riceve gli avvisi admin all'ingresso.

**f. Avvisi admin.** Con `tickengine.notify` (o op), un avviso in chat all'ingresso se c'è un problema
da segnalare, per esempio ServerCore con l'activation range attivo (se esiste per quella versione).
Il link "[hide notices]" deve inserire il comando nella chat.

**g. Segnalazione (`/tickengine report`, `ClickEvent.OpenUrl` cambia in 1.21.5).** Con un client:
- `/tickengine report` mostra quattro scelte cliccabili; "General" esegue il comando, le altre mettono
  nella chat `/tickengine report blockentity ` ecc. Tab completa tipi e mod.
- `/tickengine report entity minecraft:villager`: compare "[Open the GitHub issue]". Cliccando, il
  client chiede conferma e apre il browser su una bozza di issue con titolo e corpo già compilati
  (versioni, impostazioni cambiate, regola, profiler, elenco mod). Controllare che GitHub **non** risponda
  con un errore di URL troppo lungo con una lista di mod lunga (50+ mod): se succede, abbassare
  `MAX_URL_LENGTH` in `DiagnosticReport`. Il limite esatto di GitHub non è stato verificato.
- Il file `tickengine/reports/diagnostic-*.txt` si può trascinare nell'issue.
- Da console: niente link cliccabile, solo l'indirizzo breve della pagina e il file.
- Un giocatore senza `tickengine.report` (LuckPerms) non vede `report` e riceve un errore se lo scrive.

**h. Stacking con un giocatore (beta.6).** Fatto il 30/10 su 1.21.11 con un giocatore di test entrato dal
normale login del server (senza client grafico): grano, forbici, guinzaglio, accoppiamento, XP, mob cap,
despawn, tutto come atteso (dettagli nel README). Resta solo da guardare con un client vero che il nome
"Cow x10" compaia sopra la pila. Il resto qui sotto vale per ripetere i test sulle altre versioni:
- `/tickengine stacking mode always` e conferma; evoca 10 mucche su un punto. Guardandole da vicino si
  vede "Cow x10" sopra una sola mucca; con `stacking.alwaysShowCount true` si vede sempre.
- Grano sulla pila: si separa una mucca in amore, la pila diventa x9. Un'altra mucca separata col grano:
  le due si accoppiano e nasce un vitello, che non entra in pila finché è cucciolo.
- Uccidi la pila con la spada: muore una mucca alla volta, ognuna con carne, cuoio e **XP** (l'XP cade
  solo se uccide un giocatore: dalla console non si poteva provare).
- Pecora: le forbici tosano una pecora sola. Guinzaglio: lega una mucca sola.
- Mob cap: di notte, in un'area di soli mostri, con stacking `always`, il numero di mostri **contati
  con le pile** (`/tickengine stacking` mostra quanti mob tengono le pile) non deve superare quello di
  un server senza stacking (cap ~70 per giocatore). Dalla console non si poteva provare: lo spawn naturale
  avviene solo vicino ai giocatori.
- Despawn: allontanati oltre 128 blocchi da una pila di zombie: sparisce tutta la pila (come mob singoli).

**i. Emergenza con un giocatore (beta.6).** Pausa dello spawn verificata il 30/10 su 1.21.11 col giocatore
di test; mancano i messaggi in chat agli admin visti da un client. `/tickengine emergency enable`, poi
`/tickengine emergency start`. Di notte in superficie non devono comparire nuovi mostri finché è attiva
(spawn naturale in pausa; dalla console non si poteva provare). La view distance scende al minimo e
torna com'era con `/tickengine emergency stop`. Gli admin collegati ricevono il messaggio rosso all'avvio
e quello verde alla fine.

## 4. Jar sbagliato

Metti `tickengine-1.0.0-beta.5+1.21.1.jar` su un server 1.21.4. Atteso: Fabric si rifiuta di avviare
con un messaggio chiaro ("requires minecraft >=1.21 <=1.21.1"), senza crash a metà caricamento.
Ripeti con il jar `+1.21.11` su 1.21.10.

## 5. Config tra versioni

- Copia `config/tickengine.json5` da un server 1.21.11 a uno 1.21.1 (cambiando prima qualche valore):
  i valori devono restare uguali dopo l'avvio, senza warning.
- Su 1.21.1 o 1.21.2 metti un errore di sintassi nel file (togli una `}`) e fai `/tickengine config
  reload`: deve dare un errore, e il file deve restare **intatto** (è il ramo `setLenient`, nuovo).

---

## Esiti

| Test | 1.21 | 1.21.1 | 1.21.2 | 1.21.4 | 1.21.5 | 1.21.6 | 1.21.8 | 1.21.9 | 1.21.10 | 1.21.11 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1. Smoke dev | – | PASS (30/10, beta.6) | PASS (30/10, beta.6) | – | PASS (30/10, beta.6) | PASS (30/10, beta.6) | – | PASS (30/10, beta.6) | – | PASS (30/10, beta.6) |
| 2. Server vero | | – | – | | – | – | | – | | – |
| 3a. AI saltata | | | | | | | | | | |
| 3b. Click chat | | | | | | | | | | |
| 3c. Tetto mondo | – | | – | – | – | – | – | – | – | – |
| 3d. Permessi op | | | | | | | | | | |
| 3e. LuckPerms | | | | | | | | | | |
| 4. Jar sbagliato | – | – | – | | – | – | – | – | | – |
| 5. Config | – | | | – | – | – | – | – | – | |

`–` = non necessario per quella versione.

---

# Test del network profiler (dopo la beta.6)

Già fatto il 2026-10-31: build e 42 test unitari su tutti e 6 i nodi (5 nuovi in `NetworkWindowTest`:
divisione dei byte, ordinamento, 8 thread in parallelo); smoke test con ping di stato
(`tools/status-ping.py`) che misura `minecraft:status_response`. Lo smoke test **non** prova la
compressione né i pacchetti di gioco: servono un giocatore e i test qui sotto.

Server dev 1.21.11 (`./gradlew :1.21.11:runServer`), client 1.21.11 connesso a `localhost`, da operatore.
Dopo ogni test apri il report HTML in `versions/1.21.11/run/tickengine/reports/tickengine-network-*.html`.

**a. Base.** `/tickengine network start 30`, cammina e vola in creativa in un'area nuova.
Atteso: in testa "Chunks and light" (`level_chunk_with_light`), compressione risparmiata 50-80%,
"Other packet" con i movimenti del giocatore. Nessun errore nel log del server.

**b. Numeri veri.** Ripeti (a) con Monitoraggio Attività > Rete aperto sul Mac, guardando i byte
ricevuti al secondo dal processo java del client. Atteso: "Sent/s" del report nello stesso ordine di
grandezza (TickEngine conta compressione e lunghezza di ogni pacchetto, non TCP/IP).
Fatto il 2026-10-31 con `nettop` sul processo del server: prima della correzione il report stava 10% sotto
(mancava la lunghezza davanti a ogni pacchetto), dopo deve stare entro qualche %.

**c. Entità.** In un recinto `/summon minecraft:cow` ×50 (o `/execute` ripetuto), stai vicino,
`/tickengine network start 20`. Atteso: in "Sources" `entity minecraft:cow` in alto; passando il mouse,
`move_entity_*`/`set_entity_data`/`rotate_head`; in "Chunks" il chunk del recinto primo, "Mostly: Entity
minecraft:cow"; il click ti teletrasporta lì sopra (non dentro un blocco).

**d. Spawn delle entità (`ServerEntityMixin`).** Allontanati >10 chunk, avvia una sessione da 20 s e
torna verso il recinto. Atteso: `add_entity` contato sotto `entity minecraft:cow`, non sotto "Other".

**e. Blocchi.** Un clock di redstone veloce (osservatori contrapposti) con lampade e pistoni.
Atteso: `block minecraft:redstone_lamp` / `minecraft:observer` (block_update, section_blocks_update)
e `minecraft:piston` / `sticky_piston` con `block_event`. Il chunk del clock in testa.

**f. Block entity, suoni, particelle.** Blocco comandi a ripetizione con
`particle minecraft:flame ~ ~2 ~ 1 1 1 0 50 force`, un altro con
`playsound minecraft:block.note_block.bell block @a ~ ~ ~`, un terzo con
`data merge block <cartello vicino> {front_text:{messages:['"a"','"b"','""','""']}}` (sintassi 1.21.5+;
prima di 1.21.5 serve `'{"text":"a"}'`). Atteso: `particle minecraft:flame`, `sound
minecraft:block.note_block.bell`, `block entity minecraft:sign`, ognuno col suo chunk.

**g. Due giocatori.** Secondo client (o un amico) vicino al recinto. Atteso: i byte delle mucche circa
raddoppiano rispetto a (c).

**h. Comandi.** `network stop` prima della fine (risultati subito); `network`, `network top mods`,
`top sources`, `top packets`, `top chunks`, `network report`; `network start` due volte (secondo
rifiutato); `network start 99999` (rifiutato, massimo di `profiler.maxSessionSeconds`). Un giocatore non
operatore senza `tickengine.profile` non vede `/tickengine network`.

**i. Mod con canali propri.** Sul modpack vero (non in dev): una sessione da 60 s con 1-2 giocatori.
Atteso: le mod con rete propria compaiono come `channel <mod>:<canale>` sotto il loro nome nei "Mods".

**l. Altre versioni.** Ripeti (a), (c) e (e) almeno su **1.21.1** e **1.21.9** (nomi dei metodi del
tracker diversi: `broadcast` contro `sendToTrackingPlayers`) e su **1.21.6** (`broadcastIgnorePlayers`).

### Esiti in gioco, 1.21.11 (2026-10-31, server dev offline + client dev)

| Test | Esito |
|------|-------|
| a. Base | OK: chunk e luce 91%, compressione 85%, entità e blocchi attribuiti |
| b. Numeri veri | OK: report contro `nettop` sul server, in movimento ~3% (campioni ogni 6 s) |
| c. Entità | OK: mucche 68%, chunk giusto, click teletrasporta sopra il recinto. Trovato e corretto: mancava la lunghezza di ogni pacchetto (-10%); dopo, scarto 1% da nettop |
| d. Spawn | OK: `add_entity` sotto l'entità giusta già in (a) |
| e. Blocchi | OK: pistone (`block_event`), osservatore, lampada, suoni del pistone |
| f. Block entity, suoni, particelle | OK: cartello 20/s `block_entity_data`, `flame`. Trovato e corretto: suoni di `/playsound` (holder diretto) erano "unregistered" |
| g. Due giocatori | non fatto |
| h. Comandi e permessi | OK: doppio start rifiutato, stop anticipato (18 s), top/report, limite 600 s, senza op nessun accesso |
| i. Modpack | OK, vedi sotto |
| l. Altre versioni | 1.21.1, 1.21.9, 1.21.6 fatti (sotto). Smoke test automatico passato su tutte |

### Esiti in gioco, 1.21.1 (2026-10-31, console del server pilotata da Claude, client dev connesso)

| Test | Esito |
|------|-------|
| a. Base (teletrasporti da 48 blocchi) | OK: 297 KB/s, chunk 90%, compressione 85%; spawn non finiscono in "Other" |
| c. Entità | OK: `entity minecraft:cow`, chunk del recinto in testa |
| e. Redstone | OK: pistone, osservatore, lampada, suoni del pistone |
| f. Cartello, suono, particelle (comandi da console 18/s) | OK: sign 18.3/s, `note_block.bell` col nome (via `getLocation`), `flame` |
| Hover e click in chat | OK. Trovato e corretto: il click portava al centro del chunk (fuori da un recinto piccolo, a terra); ora porta all'ultimo pacchetto con posizione, primo punto libero sopra |

### Esiti in gioco, 1.21.9 e 1.21.6 (2026-10-31, stesso metodo di 1.21.1)

| Test | 1.21.9 (`sendToTrackingPlayers`) | 1.21.6 (`broadcast` + `broadcastIgnorePlayers`) |
|------|------|------|
| a. Base | OK: 282 KB/s, chunk 93%, compressione 85% | OK: 310 KB/s, chunk 93%, compressione 85% |
| c. Entità | OK: `entity minecraft:cow`, chunk 0,0 | OK: `entity minecraft:cow`, chunk 0,0 |
| e. Redstone | OK | OK |
| f. Cartello, suono, particelle | OK (suono: serve stare entro 16 blocchi, sennò vanilla non lo manda) | OK |
| Pacchetti di entità in "Other" | nessuno | nessuno |
| Errori nel log | 0 | 0 |

Hover e click: stesso codice di 1.21.11 (>=1.21.5), già provato lì.

### Esito col modpack (2026-10-31, ~/Desktop/TickEngine-TestServer, 1.21.11, client vero Francescor17)

- Gioco normale 60 s: `channel villager-news-addon-port:dialogue_animation` sotto **Villager News Addon Port**
  (nome dalla metadata di Fabric). Resto: mob vanilla, chunk con la posizione.
- Uscita e rientro durante una sessione da 90 s: fase di configurazione misurata. `fabric:registry/sync`
  (~32 KB, compressione 75%) sotto **Fabric API**; `c:register`, `c:version`, `minecraft:register`,
  `minecraft:brand`, `villager-news-addon-port:settings`, `fabric:custom_ingredient_sync`. Chunk dell'ingresso 78%.
- Da sistemare forse: il namespace `c` (convenzioni comuni di Fabric) compare come mod "c".
- Non provato: una mod che sostituisce la compressione (nessuna nel modpack). 0 errori nel log.

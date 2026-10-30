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
| 1. Smoke dev | – | PASS (27/10) | | – | | | – | | – | |
| 2. Server vero | | – | – | | – | – | | – | | – |
| 3a. AI saltata | | | | | | | | | | |
| 3b. Click chat | | | | | | | | | | |
| 3c. Tetto mondo | – | | – | – | – | – | – | – | – | – |
| 3d. Permessi op | | | | | | | | | | |
| 3e. LuckPerms | | | | | | | | | | |
| 4. Jar sbagliato | – | – | – | | – | – | – | – | | – |
| 5. Config | – | | | – | – | – | – | – | – | |

`–` = non necessario per quella versione.

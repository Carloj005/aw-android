# AW Anime (Android Native)

🌐 **Sito web ufficiale (Download APK 1-click & Guida installazione animata):** [https://carloj005.github.io/aw-android/](https://carloj005.github.io/aw-android/)

App Android nativa (Kotlin + Jetpack Compose + Material You 3 Expressive + Media3 ExoPlayer) basata sull'ecosistema `aw-cli`, aggiornata per il nuovo **AnimeUnity (`https://animeunity.cx`)**.

## Novità v1.4.4 — Fix Definitivo "Continua a guardare" (Anime in Corso, Interrotti Suite TV, Yu-Gi-Oh! & Tasto ✕)
- **Tasto rapido `✕` su "Continua a guardare" in Home**: permette di nascondere con 1 tocco qualsiasi anime da "Continua a guardare" (via flag persistente `hiddenFromContinueWatching` su `WatchHistoryEntry`) mantenendo intatta la cronologia in *Libreria → Cronologia* (si ripristina automaticamente se riprendi a guardare un nuovo episodio).
- **Fix anime in corso già in pari (es. *JoJo Steel Ball Run* `Ep. 10/11`)**: `WatchHistoryEntry.isCaughtUp` e l'importatore Suite TV confrontano ora con `releasedEpisodes` (episodi realmente usciti) invece di `totalEpisodes` (episodi futuri pianificati), nascondendo subito i titoli in pari fino all'uscita del prossimo episodio.
- **Rispetto flag `isStopped` / `isHidden` di Suite TV**: gli anime interrotti/abbandonati su Suite TV (es. *Azur Lane*) restano in Cronologia ma non affollano più "Continua a guardare"; quelli nascosti vengono saltati.
- **Fix collisione serie omonime & resilienza ARM**: `canUseLocalOnly` verifica ora la corrispondenza esatta per `anilistId`/`malId` (impedendo a *Yu-Gi-Oh!* 1998 di sovrascrivere *Yu-Gi-Oh! Duel Monsters*), i metadati freschi di `/api/archive` hanno priorità sulla cache locale, `lookupArmByTvdbId`/`lookupArmByTmdbId` distinguono `HTTP 404` da errori temporanei `429`/`5xx` con retry automatico, e vengono preservati i film assegnati a stagioni numerate TVDB (es. *Initial D Third Stage* = S3).

## Novità v1.4.3 — Hotfix Anti-Rate-Limit Import Suite TV, Copertine & Serie Continue
- **Fix definitivo blocco WAF `HTTP 403` dopo import `.zip` Suite TV**:
  - Importazione ristrutturata in 2 fasi: **Fase 1** pre-filtra in parallelo su ARM v2 tutti i titoli non-anime (zero chiamate verso AnimeUnity); **Fase 2** interroga AnimeUnity in modo sequenziale e cadenzato (`1` sola richiesta leggera `/api/archive?query=...&page=1` per anime con pausa `450ms`, **zero** chiamate `getEpisodes()`, riuso immediato dei titoli già presenti in libreria, salvataggio incrementale ogni 15 anime e circuit-breaker su `RateLimitedException` HTTP `403`/`429`).
  - `AnimeUnityClient` ora gestisce `RateLimitedException` su `ensureSession`, `apiGetJson`, `inertiaGet` e `getEpisodes` con singolo refresh pulito dei cookie (`MemoryCookieJar.clear()`) senza spammare 24 TLD alternativi quando AnimeUnity limita temporaneamente l'IP.
  - Anche l'esportazione Suite TV è ora cadenzata (`350ms` sequenziale) nell'arricchimento dei metadati mancanti.
- **Fix copertine mancanti**: `HttpClients.imageClient` (Coil) invia lo stesso `User-Agent` mobile del client principale e `AnimeUnityParser` legge il fallback `image_variants.*` quando `images.*` è assente.
- **Fix serie continue multi-stagione in "Continua a guardare"** (es. *Yu-Gi-Oh! Duel Monsters* 224 ep., *Yu-Gi-Oh! Zexal* 146 ep.): esclusi film/OVA/speciali dal conteggio ARM e appiattimento automatico delle stagioni TVDB verso le serie uniche su AnimeUnity; `HistoryRepository.mergeImport` ripara automaticamente anche eventuali voci parziali già importate in precedenza (`47/224` → `224/224`).

## Novità v1.4.2 — Sincronizzazione Nativa Suite TV (.zip / .ndjson) con Filtro Automatico Non-Anime
- **Importazione Nativa `suite-export-*.zip`**: *Impostazioni → Integrazione Suite TV → Importa .zip* legge direttamente l'archivio ufficiale di Suite TV (`manifest.json`, `shows.ndjson`, `movies.ndjson`), oltre a `.ndjson` singoli e Trakt `.json`.
- **Matching Preciso Multi-Stagione & Filtro Non-Anime**: reverse-mapping via ARM v2 (`/api/v2/thetvdb`, `/api/v2/themoviedb`) che scarta istantaneamente serie/film live-action non-anime, abbina ogni stagione tramite ID AniList/MAL su AnimeUnity (gestendo anche anime split-cour come *JoJo* S2/S5 e serie continue come *Yu-Gi-Oh!*), conserva i timestamp `watchedAt` reali e mostra avanzamento live durante l'importazione.
- **Esportazione Nativa `suite-export-aw-anime.zip`**: *Esporta .zip* genera l'archivio nativo Suite TV v1 (`manifest.json`, `shows.ndjson`, `movies.ndjson`) con `tvdbId` risolto e stagioni unificate per serie.
- **Note rilascio semplificate (`RELEASE_NOTES.md`)**: separazione tra changelog tecnico (`README.md`) e note brevi in italiano semplice per il popup aggiornamenti in-app.

## Novità v1.4.1 — Segnalazioni Bug Anonime, Suite TV Diretto + Import JSON, Fix Calendario Lingua & Snackbar
- **Segnalazione Bug & Idee Anonima (Zero Account GitHub)**: il modulo in *Impostazioni → Segnalazione Bug & Feedback* invia ora direttamente la segnalazione tramite relay serverless Cloudflare Worker (`aw-anime-reports`) che apre in automatico la issue sul repository senza richiedere account GitHub (con opzioni rapide Condividi, Copia e GitHub).
- **Suite TV — Apertura Diretta Pagina Anime + Importazione JSON (Issue #5)**:
  - Il tasto TV in Libreria, Scheda e Player ora risolve automaticamente l'ID **TheTVDB** dell'anime (via ARM / ani.zip) e apre direttamente la **pagina esatta dell'anime** dentro l'app nativa Suite TV (`https://www.suitetv.app/it/series/<tvdbId>`), senza copia-incolla! E nel raro fallback testuale pulisce correttamente anche i sottotitoli d'arco (es. `"Tougen Anki: Nikko Kegon Falls Arc"` → `"Tougen Anki"`).
  - Aggiunta l'**Importazione JSON da Suite TV** (*Impostazioni → Integrazione Suite TV → Importa JSON*) compatibile con export **Suite TV / TV Time JSON** e **Trakt v2 JSON**, con merge senza perdita sulla cronologia locale.
- **Fix Calendario "I miei" su lingua SUB/ITA**: se segui un anime solo in italiano (es. *JoJo Parte 7 ITA*), il calendario personalizzato non mostra più la versione duplicata in lingua originale (SUB), rispettando la lingua scelta per ogni franchise.
- **Fix messaggi in Impostazioni (Snackbar auto-dismiss)**: i messaggi di stato (es. segnalazione inviata, cache svuotata, backup/import) ora compaiono in basso come Snackbar temporanea che scompare automaticamente invece di restare fissa in alto.
- **Popup aggiornamenti con novità reali**: la finestra di aggiornamento ora formatta in modo pulito le funzionalità e i fix della release (eliminando i link grezzi `Full Changelog`), con estrazione automatica delle note da `README.md` in CI.

## Novità v1.4.0 — Calendario Personalizzato, Auto-Sequel, Random Anime, Ricerca Potenziata, Bug Report & Fix Suite TV
- **Calendario Personalizzato & Notifiche per Singolo Anime**: la tab **Uscite** ora si apre di default su **I miei** (mostrando solo gli anime che stai guardando, i Preferiti e le nuove stagioni rilevate dei tuoi franchise, con conteggio per giorno e tasto rapido *"Mostra tutto il palinsesto"*). Ogni anime nel Calendario, nella Scheda e nella Libreria ha ora la sua campanella **🔔 / 🔕** per decidere esattamente di quali anime ricevere le notifiche.
- **Rilevamento Automatico Prossima Stagione / Sequel**:
  - In ogni scheda anime viene mostrata la card **"Prossima stagione disponibile"** (e il carosello **"Stagioni & Correlati"**) grazie a `relatedAnimes`. Quando completi una stagione, la stagione successiva viene evidenziata e salvata automaticamente.
  - Se un franchise che hai già guardato/finito riceve in futuro una **nuova stagione** nel calendario o nelle ultime uscite (es. *Solo Leveling* S1 → S2 → futura S3), AW Anime la rileva, la salva automaticamente nei Preferiti, ne attiva le notifiche e ti avvisa con una notifica dedicata.
- **Anime Random ("Sorprendimi")**: nuovo pulsante 🎲 in Home e nella tab Cerca collegato al picker `/random` di AnimeUnity (salta automaticamente gli anime che hai già completato), con anteprima trama/voto/generi e tasti *"Un altro"* / *"Apri scheda"*.
- **Ricerca Potenziata**: ricerca live con debounce durante la digitazione, espansione automatica delle sigle più comuni (`aot`, `jjk`, `csm`, `mha`, `kny`, `hxh`, `fma`, `opm`, `sbr`, `sololeveling`…), esplorazione diretta per **Genere** e **Formato** (`TV`, `Film`, `OVA`, `ONA`) anche a barra vuota, e ordinamento personalizzabile (`Cronologico`, `Voto ★`, `Più recenti`, `Popolari`).
- **Segnalazione Bug & Idee In-App**: nuova sezione in *Impostazioni → Segnalazione Bug & Feedback* per inviare bug o proposte direttamente su GitHub con diagnostica dispositivo precompilata (versione app, API Android, dominio attivo) o copiare il report negli appunti.
- **Fix apertura app nativa Suite TV**: il tasto TV nella Libreria (e nelle schede/player) ora apre direttamente l'app nativa Android `com.nicolasbordeaux.suitetv` invece del browser, copiando il titolo pulito negli appunti per cercarlo subito.

## Novità v1.3.2 — Link Supportati AnimeUnity & Fix "Continua a guardare"
- **Apertura diretta link AnimeUnity (Issue #3)**: toccando un link `animeunity.*` ricevuto o dal browser (scheda `/anime/<id>-<slug>`, episodio diretto `/anime/<id>-<slug>/<videoId>`, `/calendario`, `/archivio?title=...`, `/top-anime`), AW Anime si apre direttamente sul contenuto corrispondente (con riconoscimento automatico SUB/ITA sugli episodi condivisi e tasto **"Attiva"** in *Impostazioni → Link supportati AnimeUnity* per Android 12+).
- **Fix "Continua a guardare" su stagioni finite (Issue #4)**: quando completi l'ultimo episodio di una stagione (o l'ultimo episodio uscito di un anime in corso), il titolo scompare automaticamente dal carosello *"Continua a guardare"* in Home restando comunque salvato in *Libreria → Cronologia* (e ricompare da solo se esce un nuovo episodio).

## Novità v1.3.1 — Hotfix R8 Full Mode (WorkManager / Room / Coil)
- **Fix crash all'avvio su Android (`v1.3.0`)**: R8 Full Mode di AGP 9 rimuoveva il costruttore riflessivo di `WorkDatabase_Impl` (Room/WorkManager), `WorkerParameters` e l'interfaccia `coil.ImageLoaderFactory` su `AwAnimeApp`. Aggiunte regole ProGuard mirate per Room/WorkManager/Startup/SavedState/Coil e inizializzazione `by lazy` crash-safe su `AwAnimeApp.container`.

## Novità v1.3.0 — Calendario Uscite, Notifiche Preferiti, Suite TV & Clean Code Hardening
- **Calendario Uscite Settimanali**: nuova tab **Uscite** collegata in tempo reale al palinsesto settimanale (`/api/calendar`) con selettore giorno (`Oggi`, giorni della settimana, `TBA`), filtri rapidi (`Tutti`, `SUB`, `ITA`, `Preferiti`), orari locali stimati, stato **Disponibile / In ritardo / In pausa** e avvio diretto degli episodi usciti.
- **Notifiche Locali Nuovi Episodi Preferiti**: worker periodico `WorkManager` configurabile in *Impostazioni → Notifiche Preferiti* (`1h / 3h / 6h / 12h` + tasto **"Controlla Preferiti ora"**) con supporto permesso `POST_NOTIFICATIONS` su Android 13+, baseline anti-spam al primo avvio e tap sulla notifica che apre direttamente la scheda dell'anime.
- **Integrazione Suite TV (`https://www.suitetv.app/it`)**:
  - Chip **"Traccia su Suite TV"** in ogni scheda anime, azione rapida nella Libreria e nella card fine episodio del player (apre direttamente l'app nativa Android `com.nicolasbordeaux.suitetv` tramite Android App Links, con fallback web).
  - Esportazione cronologia episodi completati in formato **Trakt JSON** compatibile con l'importatore integrato di **Suite TV** (*Impostazioni → Integrazione Suite TV → Esporta JSON*).
- **Architettura Clean Code, Sicurezza & Stabilità (unione miglioramenti PR #1 + PR #2)**:
  - **Sicurezza fail-closed**: `AppUpdater` blocca l'aggiornamento se `.sha256` o firma certificato mancano o non combaciano e limita gli host GitHub ai 4 domini ufficiali; `UrlSecurityPolicy` con regex ancorate anti-bypass su embed/DoH; `DohFallbackDns` con protezione IPv6 completa (`::ffff:0:0/96`, `::/96`, `100::/64`, NAT64 `64:ff9b::/96`); query GraphQL Anime-Skip parametrizzate con `variables` JSON.
  - **Zero I/O su Main Thread**: `JsonFileStore<T>` asincrono su `Dispatchers.IO` con salvataggio `.corrupt.bak`, backup SAF asincrono con limite anti-OOM 10 MB, merge import cronologia senza perdita degli episodi più vecchi, calcolo cache asincrono.
  - **Architettura modulare & Resilienza**: container DI `AppContainer` (eliminato `AwAnimeApp.instance`), `PlayerViewModel` + `PlayerOverlays`, `AnimeUnityParser` + `VixCloudExtractor` + `SkipTimeRemoteDataSource`, `DownloadWorker` (`WorkManager` foreground con ripresa HTTP `Range` e `call.cancel()` immediato), `NetworkMonitor` offline fast-fail, `HttpClients` con pool condiviso, `runCatchingCancellable`, `collectAsStateWithLifecycle` e workflow CI Android.

## Novità v1.2.3 — PiP Nativo Completo & Zone Gesture Sicure
- **Fix Picture-in-Picture (PiP)**:
  - Il video **non si mette più in pausa** entrando in finestra flottante (pausa spostata correttamente su `ON_STOP` anziché `ON_PAUSE`).
  - Aggiunti **controlli nativi direttamente nella finestra flottante PiP** (`-10s`, `Pausa / Riproduci`, `+10s`) con aggiornamento dinamico dell'icona e auto-PiP intelligente solo durante la riproduzione attiva.
- **Zone gesture Luminosità / Volume ergonomiche**:
  - Luminosità attiva solo nella fascia interna sinistra (`10%–35%` orizzontale, `18%–82%` verticale) e Volume solo nella fascia interna destra (`65%–90%` orizzontale, `18%–82%` verticale), lasciando liberi i bordi di sistema (tendina notifiche in alto, gesture Home/uscita in basso, gesture Indietro sui bordi esterni e area centrale neutra).

## Novità v1.2.2 — Motore Smart Skip Universale Multi-Sorgente
- **Fix definitivo falsi "Salta Sigla" su qualsiasi anime** (es. Ep. 1 senza sigla come *Chainsaw Man*, *Solo Leveling*, *Frieren*, *Demon Slayer*, o falsi ED precoci):
  - Nuovo motore `SkipTimeValidator` con **validazione incrociata in parallelo** tra **VixCloud** (priorità 1, audio-fingerprinted), **AniSkip v2** (durata esatta + consenso globale) e **Anime-Skip GraphQL** (lookup esatto tramite `anilistId`).
  - Controllo rigoroso sulla durata reale dell'episodio (`±12s`), eliminazione automatica di auto-conflitti, veto su episodi senza sigla e limiti strutturali/posizionali su Intro, Outro e Recap.
  - Su Ep. 1 con sorgente non verificata l'Auto-Skip non scatta alla cieca (mostra il tasto manuale) e il pulsante fluttuante **"Salta Sigla"** ora include una rapida **`✕`** per nasconderlo con 1 tocco.

## Novità v1.2.1 — Fix Definitivo Skip Rapidi HLS & Bugfix Suite
- **Fix critico streaming HLS reale (stop fallback MP4 + HTTP 503)**: impostato esplicitamente `MimeTypes.APPLICATION_M3U8` sugli URL `/playlist/<id>` di VixCloud (privi di estensione `.m3u8`) così Media3 usa davvero **`HlsMediaSource`** su `au-u1-01` (limite 16 connessioni) invece di ricadere di nascosto sull'MP4 (`au-d1-01`, limite 2 connessioni → `HTTP 503` dopo skip rapidi).
- **`ResilientStreamErrorPolicy` + auto-refresh token**: fino a 5 retry con backoff su `HTTP 503`/`429`, pool connessioni dedicato separato dai download in background, rinnovo automatico singolo del token conservando il minutaggio esatto e codice HTTP mostrato chiaramente in caso di errore.
- **Altri 7 bug risolti**: clamp sicuro dei seek entro `durationMs` (evita `HTTP 416`), fix chiusura stantia in `DisposableEffect` che salvava il progresso sul primo episodio dopo auto-avanzamento, fix audio residuo alla chiusura PiP (`ON_STOP`), `setAutoEnterEnabled` fluido su Android 12+, completamento garantito su `STATE_ENDED`, blocco auto-hide durante trascinamento Slider, deduplicazione chiavi Home anti-crash e controllo redirect hop in `AppUpdater`.

## Novità v1.2.0 — Sicurezza, Qualità & Nuove Funzionalità
### 🔒 Hardening & Sicurezza
- **Aggiornamenti verificati crittograficamente**: l'updater controlla solo host HTTPS fidati GitHub, verifica il checksum **SHA-256** del file `.apk.sha256` pubblicato nella release e controlla tramite `PackageManager` che il **certificato di firma digitale** dell'APK scaricato coincida con quello dell'app installata prima ancora di aprire l'installer Android.
- **HTTPS Strict + Network Security Config**: traffico in chiaro (`http://`) bloccato a livello OS e OkHttp, fiducia solo alle CA di sistema (nessun certificato utente MITM).
- **DNS-over-HTTPS anti-SSRF IPv4 + IPv6** con cache a TTL (10 min) e blocco rigoroso di indirizzi privati, loopback, link-local, ULA e CGNAT.
- **Isolamento cookie & allowlist domini**: client OkHttp separato senza cookie per domini terzi (`VixCloud`, `AniSkip`), validazione stretta di origini `animeunity.*` / `vixcloud.*` e controllo canonical path-traversal sui download.
- **R8 Minification & Resource Shrinking**: build Release offuscata, alleggerita e priva di log di debug, con regole di backup controllate (`backup_rules.xml` / `data_extraction_rules.xml`).

### ✨ Nuove Funzionalità & Miglioramenti
- **Preferiti / La mia Libreria**: aggiungi anime ai preferiti con il tasto cuore, carosello dedicato in Home e tab **Libreria** unificata (**Cronologia** + **Preferiti**).
- **Picture-in-Picture (PiP) nativo Android** + **Audio Focus & pausa automatica** allo scollegamento cuffie Bluetooth/cavo e al passaggio in background.
- **Backup & Ripristino Locale (JSON)**: esporta e importa Cronologia, Preferiti e Impostazioni in un clic via Storage Access Framework (zero account richiesti).
- **Ricerca & UX**: **Pull-to-Refresh** nativo in Home, filtri rapidi sui risultati di ricerca (`Tutti`, `SUB`, `ITA`, `In corso`, `Finiti`), cronologia ricerche persistente, tasto **Riprova** sui download falliti, **Download solo su Wi-Fi**, pulizia cache, pulsante **Condividi** e link esterni **AniList / MyAnimeList**.

## Funzionalità Core (`v1.0` – `v1.1`)
- **Smart Skip OP/ED (stile Netflix)** con timestamp reali VixCloud + fallback AniSkip API v2, pulsante fluttuante **"Salta Sigla"** e Auto-Skip opzionale con **"Annulla"**.
- **Material You 3 Expressive** (colori dinamici Android 12+, tema Sistema/Scuro/Chiaro, Hero Carousel) e **nuova icona Torii + Play** con supporto Android 13+ **Icone a tema** (`<monochrome>`).
- **Player 1080p FHD HLS** con swipe verticali Luminosità/Volume, Drawer Episodi, Blocco Schermo e countdown **"Prossimo episodio tra 5s"**.
- **Aggiornamenti Automatici In-App** da GitHub Releases (`Carloj005/aw-android`).

## Compilazione APK in locale
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```
Gli APK vengono generati in:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

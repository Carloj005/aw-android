# AW Anime (Android Native)

App Android nativa (Kotlin + Jetpack Compose + Material You 3 Expressive + Media3 ExoPlayer) basata sull'ecosistema `aw-cli`, aggiornata per il nuovo **AnimeUnity (`https://animeunity.cx`)**.

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

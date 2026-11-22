# AW Anime (Android Native)

App Android nativa (Kotlin + Jetpack Compose + Media3 ExoPlayer) basata sull'ecosistema `aw-cli`, aggiornata per il nuovo **AnimeUnity (`https://animeunity.cx`)**.

## Funzionalità principali
- **Home**: Ultime uscite (`Tutti`, `SUB`, `ITA`), classifica **Tendenze** (`/api/top-anime`) e carosello **Continua a guardare**.
- **Ricerca intelligente**: ricerca sull'intero archivio + autocomplete con **tolleranza ai refusi** (es. `jojo steal ball run`) e **ordinamento cronologico automatico delle stagioni** (SUB seguito da ITA).
- **Scheda Anime**: banner, poster, metadati completi, generi, studio, trama espandibile, selettore rapido lingua **SUB / ITA**, lista episodi paginata (supporta serie da 1000+ episodi) con stato di visione e download.
- **Player Video Nativo (Media3 / ExoPlayer)**:
  - Streaming **1080p FHD HLS** con selezione automatica della qualità massima e fallback multi-sorgente.
  - Buffer ottimizzato per skip rapidi senza blocchi.
  - Full-screen landscape immersivo, doppio tap `-10s` / `+10s`, pulsante rapido `+85s Salta Sigla`, controllo velocità (`0.75x`–`2.0x`), aspect ratio (`Adatta` / `Zoom` / `Riempi`), episodi Precedente/Successivo e ripresa automatica.
- **Cronologia locale**: salvataggio atomico del progresso per episodio (`history.json`), avanzamento automatico al prossimo episodio al completamento (`>= 90%`).
- **Download Offline**: download diretto MP4 1080p su dispositivo con barra di avanzamento, gestione coda/eliminazione e riproduzione automatica offline dal player.
- **Rete resiliente**: DNS-over-HTTPS automatico (Cloudflare / Google) contro blocchi DNS ISP e rilevamento automatico di eventuali cambi dominio.

## Compilazione APK
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```
Gli APK vengono generati in:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

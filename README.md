# AW Anime (Android Native)

App Android nativa (Kotlin + Jetpack Compose + Material You 3 Expressive + Media3 ExoPlayer) basata sull'ecosistema `aw-cli`, aggiornata per il nuovo **AnimeUnity (`https://animeunity.cx`)**.

## Novità v1.1.0 & Funzionalità principali
- **Smart Skip OP/ED (stile Netflix)**:
  - Rilevamento automatico dei timestamp reali direttamente dallo stream **VixCloud** (`intro`, `outro`, `recap`) con fallback automatico su **AniSkip API v2** (via `mal_id`).
  - Pulsante fluttuante **"Salta Sigla"** / **"Salta Outro"** / **"Salta Recap"** visibile nell'intervallo reale della sigla (anche con controlli nascosti).
  - Supporto opzionale **Auto-Skip OP/ED** attivabile dalle Impostazioni con pulsante **"Annulla"** per tornare indietro.
  - Fallback manuale `+85s` mostrato solo se nessun timestamp è disponibile.
- **Interfaccia Material You 3 Expressive**:
  - Supporto completo **Colori Dinamici Android 12+** (`dynamicDarkColorScheme` / `dynamicLightColorScheme`) attivo di default con tema automatico **Sistema / Scuro / Chiaro**.
  - Nuovo **Hero Carousel** full-bleed in Home, card arrotondate Material 3, badge qualità/lingua e schermata **Impostazioni**.
- **Player Video Nativo Potenziato (Media3 / ExoPlayer)**:
  - Streaming **1080p FHD HLS** alla massima qualità con buffer ottimizzato per skip rapidi senza blocchi.
  - **Gesture verticali**: swipe sinistro per Luminosità, swipe destro per Volume media con HUD animato.
  - **Pannello Episodi in-player**, **Blocco Schermo** anti-tocchi accidentali, card countdown **"Prossimo episodio tra 5s"** durante l'outro, doppio tap `-10s` / `+10s`, velocità (`0.75x`–`2.0x`) e aspect ratio.
- **Nuova Icona Anime + Icone a Tema (Android 13+)**:
  - Icona adattiva ispirata alla cultura giapponese (**Torii Gate + Sole + Play**) con livello dedicato `<monochrome>` per **Icone a tema / Material You**.
- **Aggiornamenti Automatici In-App (GitHub Releases)**:
  - Controllo automatico dell'ultima release da `Carloj005/aw-android` all'avvio e dalle Impostazioni, download diretto dell'APK con barra di avanzamento e avvio dell'installer nativo Android via `FileProvider`.
- **Ricerca, Cronologia & Download Offline**:
  - Ricerca tollerante ai refusi con ordinamento cronologico delle stagioni (SUB prima di ITA), cronologia atomica locale (`history.json`), download MP4 1080p offline e rete resiliente con DNS-over-HTTPS automatico.

## Compilazione APK in locale
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```
Gli APK vengono generati in:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

Se è presente `~/.aw-android-signing/signing.properties` (o le variabili ambiente `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), la build `release` viene firmata automaticamente con la chiave persistente così gli aggiornamenti futuri mantengono tutti i dati.

## Pubblicazione Automatica Release su GitHub
Il workflow [`.github/workflows/release.yml`](.github/workflows/release.yml) compila, testa e pubblica automaticamente l'APK firmato su GitHub Releases ad ogni push di tag `v*` (es. `v1.1.0`, `v1.2.0`).

Configura i 4 **Repository Secrets** su GitHub (`Settings -> Secrets and variables -> Actions`):
- `KEYSTORE_BASE64`: il file `.jks` codificato in Base64
- `KEYSTORE_PASSWORD`: password del keystore
- `KEY_ALIAS`: alias della chiave (es. `aw-anime`)
- `KEY_PASSWORD`: password della chiave

Per rilasciare una nuova versione:
1. Incrementa `versionCode` e `versionName` in `app/build.gradle.kts`.
2. Fai commit, crea il tag e pusha:
   ```bash
   git tag v1.2.0
   git push origin main --tags
   ```
3. L'app rileverà automaticamente il nuovo aggiornamento alla prossima apertura.

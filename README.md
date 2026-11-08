# mcServer4all v3.0.0 — Guida Completa di Utilizzo

**`mcServer4all`** (v3.0 — *Paradigma Single-JVM & Pack-Native*) permette a un gruppo di amici di condividere **uno o più mondi Minecraft moddati** (Forge, NeoForge, Fabric, Quilt, CurseForge, Modrinth, FTB) **senza pagare un server dedicato 24/7** e **dimezzando il consumo di RAM e spazio su disco**.

Ogni mondo è un'entità autonoma con il proprio **modpack bloccato (*lockfile* `mods.lock.json`)** e la propria **ricetta (`world.json`)**:
1. Chi crea il mondo seleziona il modpack ed esporta un piccolo file d'invito **`.mcsync`** (che include automaticamente coordinate Git e API Key CurseForge).
2. Gli amici importano il file `.mcsync`: il mondo viene registrato, le mod vengono scaricate in parallelo a 8 thread dai CDN ufficiali nella cache condivisa e l'istanza **Prism Launcher** (`mcsync-<worldId>`) viene creata da sola.
3. Per giocare basta **un clic**:
   - Se il mondo è **LIBERO**, si diventa **Host in modalità Single-JVM (Client-as-Server)**: il mondo viene aperto direttamente nell'Integrated Server del proprio client Minecraft con esposizione automatica su Tailscale (`25565`), risparmiando il **50% della RAM** e consentendo il **Fast Handover** tornando semplicemente al Menu Principale!
   - Se qualcuno sta già ospitando (`ONLINE`), ci si unisce automaticamente come **Client** al suo IP Tailscale.

---

## Indice

1. [Novità Principali di mcServer4all v3.0](#1-novità-principali-di-mcserver4all-v30)
2. [Prerequisiti e Prima Configurazione](#2-prerequisiti-e-prima-configurazione)
3. [Flusso d'Uso Completo (Nuova GUI Animata a 3 Schede)](#3-flusso-duso-completo-nuova-gui-animata-a-3-schede)
   - [A. Creare un Nuovo Mondo (Host Iniziale)](#a-creare-un-nuovo-mondo-host-iniziale)
   - [B. Esportare e Condividere l'Invito `.mcsync`](#b-esportare-e-condividere-linvito-mcsync)
   - [C. Importare l'Invito (Amici)](#c-importare-linvito-amici)
   - [D. Giocare in 1 Clic (Single-JVM Host o Client Automatico)](#d-giocare-in-1-clic-single-jvm-host-o-client-automatico)
   - [E. Aggiornare il Modpack e Fare Rollback](#e-aggiornare-il-modpack-e-fare-rollback)
4. [Guida ai Comandi CLI (Terminale / Headless)](#4-guida-ai-comandi-cli-terminale--headless)
5. [Architettura Repository, Pack-Native Sync e Single-JVM](#5-architettura-repository-pack-native-sync-e-single-jvm)
6. [Risoluzione Problemi (Troubleshooting & Centro Diagnostico)](#6-risoluzione-problemi-troubleshooting--centro-diagnostico)

---

## 1. Novità Principali di `mcServer4all` v3.0

* **Single-JVM Client-as-Server (`IntegratedHostController`)**: quando giochi come Host, non vengono più avviate due JVM separate (Server dedicato + Client). Il mondo viene eseguito nell'Integrated Server del tuo client Minecraft, aperto automaticamente sulla porta Tailscale `25565` tramite bridge TCP e con `pauseOnLostFocus:false` per non mettere in pausa il server durante `Alt+Tab`. **Risparmio del 50% di RAM (6–8 GB invece di 14–16 GB)!**
* **Fast Handover dal Menu Principale**: per cedere l'host a un amico non serve chiudere Minecraft (evitando minuti di riavvio del modpack). Basta cliccare *"Salva e torna al menu principale"*: `mcServer4all` intercetta la chiusura del mondo, carica il delta Zstd, rilascia il lock e ti permette di rientrare subito come Client!
* **Sanitizzazione Automatica `level.dat` (`LevelDatCleaner`)**: il tag NBT `Data.Player` viene rimosso automaticamente da `level.dat` ad ogni sync, garantendo che qualsiasi amico diventi Host usi sempre e solo il proprio inventario/posizione in `playerdata/<UUID>.dat`.
* **Risoluzione Batch CurseForge & Murmur2 Fingerprinting (`SourceResolver`)**:
  - Una singola chiamata batch `POST /v1/mods/files` e `POST /v1/fingerprints/432` risolve istantaneamente 300+ mod in meno di 1 secondo.
  - Costruzione automatica dell'URL di fallback diretto `https://edge.forgecdn.net/files/...` per le mod con distribuzione di terze parti disabilitata.
  - Download parallelo su **8 thread** con barra di avanzamento fluida `[X/N] - %`.
* **Bundle Unico `overrides.partXX.tar.zst` (< 75 MB) (`OverridesBundler`)**: le cartelle `config/`, `kubejs/`, `defaultconfigs/` vengono impacchettate in volumi `.tar.zst` sotto i 75 MB (compatibili con il limite di 100 MB di GitHub), proteggendo le impostazioni personali del giocatore (`options.txt`, keybind, audio, `*-client.toml`) con policy *Seed-Only*.
* **Checkout Git Unificato (`.mcsync/git-store-<hash>`)**: tutti i mondi condividono un'unica working tree Git locale con fast-path istantaneo sui blob già presenti e commit batch atomici.
* **Nuova GUI Animata a 60 FPS (`UiAnimations`)**:
  - Header con badge neon pulsanti per **Giocatore**, **Tailscale P2P** e **Stato Lock**.
  - Vista unificata **`🌍 Mondi & Avvio`** con **Hero Button** animato, barra di progresso fluida a 60 FPS e **Console Live a scomparsa**.
  - Scheda **`⚙ Impostazioni`** semplificata alle sole **4 impostazioni essenziali** (rimossa la gestione manuale della Whitelist, ora protetta a livello di rete da Tailscale).

---

## 2. Prerequisiti e Prima Configurazione

### Passo 1 — Avvio del Launcher
* **Windows**: fai doppio clic su `launch.bat`.
* **Linux / macOS**: esegui `./launch.sh`.

> Se sul PC non è presente **Java 21+**, lo script di avvio scarica automaticamente una copia portatile di **Eclipse Temurin OpenJDK 21** dentro `.tools/jdk-21` senza toccare il Java di sistema.

### Passo 2 — Rete Tailscale (P2P tra Amici)
1. Installa [Tailscale](https://tailscale.com/) su tutti i PC dei giocatori (oppure esegui `launch.bat setup` per installarlo automaticamente insieme alla regola del Firewall di Windows sulla porta TCP `25565`).
2. Assicurati che tutti i PC siano nella **stessa Tailnet** o abbiano condiviso il nodo tramite **Node Sharing** nella console [Tailscale Admin](https://login.tailscale.com/admin/machines).
3. Verifica che `tailscale ping <IP_TAILSCALE_AMICO>` risponda.

### Passo 3 — Configurazione Iniziale (`⚙ Impostazioni`)
Apri la scheda **`⚙ Impostazioni`** nella GUI di `mcServer4all`:
1. **Nickname Giocatore (`playerId`)**: imposta il tuo nickname Minecraft (l'IP Tailscale viene rilevato automaticamente su `auto`).
2. **Memoria RAM**: scegli con gli slider i GB di RAM Min/Max da allocare a Minecraft.
3. **Solo per chi crea il primo mondo** (gli amici riceveranno tutto tramite il file d'invito `.mcsync`!):
   * Crea una repository **Privata** su GitHub (es. `https://github.com/tuo-utente/mc-worlds.git`).
   * Crea un **Fine-grained Personal Access Token** limitato a quella sola repository con permesso **`Contents: Read and write`**.
   * Inserisci `gitRemoteUrl`, `gitToken` ed eventualmente la tua **`curseforgeApiKey`**, clicca **"Testa Connessione"** e poi **"💾 SALVA IMPOSTAZIONI"**.

---

## 3. Flusso d'Uso Completo (Nuova GUI Animata a 3 Schede)

L'interfaccia grafica di `mcServer4all` v3.0 è progettata per essere immediata e zero-click, organizzata in **3 sole schede**:
1. **`🌍 Mondi & Avvio`**: vista principale unificata con card di stato, tabella mondi, pulsante Hero animato, barra di progresso `[X/N] - %` e cassetto Console/Log a scomparsa.
2. **`⚙ Impostazioni`**: le 4 impostazioni fondamentali (Giocatore/Tailscale, Slider RAM, Credenziali Git, CurseForge API Key).
3. **`🩺 Diagnostica`**: analizzatore automatico dei log e riparazione guidata a 1 clic.

---

### A. Creare un Nuovo Mondo (Host Iniziale)

1. Nella scheda **`🌍 Mondi & Avvio`**, clicca **"+ Crea Mondo..."**.
2. Compila i campi del wizard:
   * **Nome Visualizzato**: es. `StoneBlock 3 con gli amici`.
   * **ID Mondo (slug)**: es. `stoneblock3` (generato automaticamente dal nome del file selezionato).
   * **Sorgente Modpack Client**:
     1. **Archivio CurseForge (`.zip`) o Modrinth (`.mrpack`)**: risolve tutte le mod in batch e impacchetta `overrides/` in `.tar.zst`.
     2. **Istanza Prism Launcher esistente**: legge `mmc-pack.json`, `mods/` e `config/` risolvendo gli hash su Modrinth e CurseForge (Murmur2) per caricare su Git solo i metadati!
     3. **Cartella manuale**: una cartella con `mods/`, `config/` e `mmc-pack.json`.
   * **Risorse Gioco**: imposta RAM Min/Max (MB) e porta (default `25565`).
   * Lascia attiva la spunta **"Esporta subito il file d'invito (.mcsync) dopo la creazione"**.
3. Premi **OK**: `mcServer4all` genererà `world.json` e `mods.lock.json`, creerà l'istanza Prism `mcsync-<worldId>` e pubblicherà il mondo in un singolo commit atomico.

---

### B. Esportare e Condividere l'Invito `.mcsync`

* Seleziona il mondo e clicca **"Esporta Invito (.mcsync)..."** (se non già esportato automaticamente).
* Invia il file `<worldId>.mcsync` ai tuoi amici su una chat privata (es. DM Discord).
* Il file `.mcsync` include automaticamente le coordinate Git e la `curseforgeApiKey`!

---

### C. Importare l'Invito (Amici)

Chi riceve il file `.mcsync`:
1. Apre `mcServer4all` e clicca **"Importa Invito (.mcsync)..."**.
2. Seleziona il file `.mcsync`.
3. `mcServer4all` registra il mondo, salva automaticamente le credenziali Git e la `curseforgeApiKey`, scarica le mod in parallelo a 8 thread dai CDN ufficiali e crea l'istanza Prism **`mcsync-<worldId>`**.

---

### D. Giocare in 1 Clic (Single-JVM Host o Client Automatico)

1. Seleziona il mondo nella tabella e premi il grande pulsante animato in basso:
   * **`▶ GIOCA ORA IN '...' (SINGLE-JVM HOST)`** se il mondo è libero:
     - Acquisisce il lock atomico, scarica il delta Zstd del mondo, rimuove `Data.Player` da `level.dat`, copia il salvataggio nell'istanza Prism e avvia una **singola JVM** aprendo il mondo su Tailscale (`25565`).
   * **`▶ UNISCITI AL MONDO '...' (CLIENT)`** se un amico sta già ospitando:
     - Allinea l'istanza Prism al lockfile e avvia Minecraft collegandosi direttamente all'IP Tailscale dell'Host.
2. **Passaggio Rapido dell'Host (Fast Handover)**:
   - Quando l'Host vuole passare il server a un amico senza chiudere il modpack, basta premere `ESC -> Salva e torna al menu principale`.
   - `mcServer4all` rileva la chiusura del mondo integrato, carica il delta dei salvataggi, rilascia il lock e permette a un altro amico di diventare subito Host!

---

### E. Aggiornare il Modpack e Fare Rollback

1. A mondo libero, seleziona il mondo e clicca **"Aggiorna Modpack..."**.
2. Scegli se aggiornare dalla tua istanza Prism (`mcsync-<worldId>`) o da un nuovo `.zip`/`.mrpack`.
3. Approva il **Diff** (`[+]`, `[-]`, `[~]`): verrà creato un backup pre-aggiornamento automatico e pubblicato il nuovo `mods.lock.json` (`vN+1`).
4. Per tornare indietro in qualsiasi momento, clicca **"Rollback Modpack..."** e seleziona la versione precedente.

---

## 4. Guida ai Comandi CLI (Terminale / Headless)

Tutte le funzioni della GUI sono disponibili anche da riga di comando tramite `launch.bat` (Windows) o `./launch.sh` (Linux/macOS):

```bat
:: --- Avvio e Gioco ---
launch.bat                                :: Apre l'interfaccia grafica (GUI)
launch.bat --no-gui                       :: Avvia la sessione (Host Single-JVM o Client) sul mondo attivo
launch.bat join <worldId>                 :: Seleziona <worldId> e avvia subito la sessione
launch.bat join-file invito.mcsync        :: Importa un file d'invito .mcsync e configura il mondo
launch.bat join-file invito.mcsync --run  :: Importa l'invito e avvia subito il gioco
launch.bat --no-gui --no-client           :: Modalità Server Dedicato Headless (senza client grafico)

:: --- Gestione Mondi ---
launch.bat world list                     :: Elenca i mondi registrati con stato live e versione modpack
launch.bat world create stoneblock3 --client-pack pack.mrpack --name "StoneBlock 3" --export-invite
launch.bat world select <worldId>         :: Imposta il mondo predefinito
launch.bat world export <worldId> [file]  :: Esporta il file d'invito <worldId>.mcsync
launch.bat world import <invito.mcsync>   :: Importa un file d'invito .mcsync
launch.bat world remove <worldId>         :: Rimuove il mondo da questo PC

:: --- Gestione Modpack Bloccato ---
launch.bat modpack update [worldId] --client-pack nuovo-pack.mrpack
launch.bat modpack versions [worldId]     :: Mostra la cronologia delle versioni del modpack
launch.bat modpack rollback [worldId] [v] :: Ripristina la versione v (o la precedente se omessa)

:: --- Utilità, Backup Mondo e Diagnostica ---
launch.bat setup                          :: Installa/verifica Java 21, Git, Prism Launcher, Tailscale e Firewall
launch.bat status                         :: Mostra stato lock, versione mondo e stato modpack
launch.bat list-backups                   :: Elenca i backup del mondo attivo
launch.bat restore <backupKey>            :: Ripristina un backup del mondo
launch.bat force-unlock                   :: Rimuove forzatamente un lock rimasto appeso
```

---

## 5. Architettura Repository, Pack-Native Sync e Single-JVM

Nella repository Git (branch `mcsync-state`), `mcServer4all` v3.0 mantiene una struttura ultraleggera (< 100 MB per limite GitHub, zero duplicazione dei `.jar` pubblici):

```text
<repo>/                                      (branch: mcsync-state — 1 solo commit orphan)
├── registry.json                            # Indice dei mondi presenti nel repository
└── worlds/
    └── <worldId>/
        ├── world.json                       # Ricetta: MC, loader, versione loader, Java, RAM, porta
        ├── mods.lock.json                   # Lockfile v2 (hash SHA-256, URL CDN CurseForge/Modrinth, overridesBundles)
        ├── locks/                           # Storico versioni lockfile per rollback istantaneo (v1.json, v2.json...)
        ├── lock.json                        # Lock atomico P2P dell'Host attivo
        ├── manifest.json                    # Indice file del salvataggio mondo per sync differenziale
        ├── blobs/sha256/<sha256>            # Volumi overrides.partXX.tar.zst (< 75 MB) e sole mod custom non-CDN
        ├── world_blobs/<sha256>.zst         # Blob compressi Zstd dei file del mondo (region, data, level.dat sanitizzato)
        └── backups/                         # Backup periodici e pre-aggiornamento modpack
```

---

## 6. Risoluzione Problemi (Troubleshooting & Centro Diagnostico)

In caso di problemi, apri la scheda **`🩺 Diagnostica`** nella GUI: l'analizzatore euristico ([`LogAnalyzer`](file:///home/pietro/Desktop/code/mcServer4All/src/main/java/com/mcsync/diagnostics/LogAnalyzer.java)) individua l'errore e offre un pulsante **"🔧 Applica Fix Automatico"**.

| Problema / Messaggio | Causa | Soluzione |
|---|---|---|
| **`Connection timed out: getsockopt`** | Il server è in ascolto sull'IP Tailscale ma il client locale punta a `127.0.0.1`. | Gestito automaticamente da `effectiveClientAddress()`; clicca **"Allinea IP Client a Tailscale"** in *Diagnostica*. |
| **`player_X sta ospitando il mondo: riprova a fine sessione`** | Hai provato ad aggiornare il modpack mentre un amico sta giocando in quel mondo. | Attendi che la sessione su quel mondo termini e che il lock torni `LIBERO`. |
| **Sessione bloccata (`STALE LOCK`) dopo un crash del PC Host** | L'Host si è spento senza rilasciare `lock.json`. | Dopo `lockTimeoutSeconds` (120s) il launcher subentra da solo; per sbloccare subito premi **"Sblocca Lock"** nella scheda *Mondi & Avvio* o *Diagnostica*. |

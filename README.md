# mcServer4all v2.0.0 — Guida Completa di Utilizzo

**`mcServer4all`** permette a un gruppo di amici di condividere **uno o più mondi Minecraft moddati** (Forge, NeoForge, Fabric, Quilt, CurseForge, Modrinth, FTB) **senza pagare un server dedicato 24/7**.

Ogni mondo è un'entità autonoma con il proprio **modpack bloccato (*lockfile* `mods.lock.json`)** e la propria **ricetta server (`world.json`)**:
1. Chi crea il mondo seleziona il modpack ed esporta un piccolo file d'invito **`.mcsync`**.
2. Gli amici importano il file `.mcsync`: il mondo viene registrato, le mod vengono scaricate nella cache condivisa e l'istanza **Prism Launcher** (`mcsync-<worldId>`) viene creata da sola.
3. Per giocare basta **un clic**: se il mondo è libero si diventa **Host** (il server viene costruito automaticamente dalla ricetta e il mondo sincronizzato via delta); se qualcuno sta già ospitando ci si unisce come **Client** direttamente al suo IP Tailscale.

---

## Indice

1. [Novità Principali di mcServer4all v2.0](#1-novità-principali-di-mcserver4all-v20)
2. [Prerequisiti e Prima Configurazione](#2-prerequisiti-e-prima-configurazione)
3. [Flusso d'Uso Completo (GUI Desktop)](#3-flusso-duso-completo-gui-desktop)
   - [A. Creare un Nuovo Mondo (Host Iniziale)](#a-creare-un-nuovo-mondo-host-iniziale)
   - [B. Esportare e Condividere l'Invito `.mcsync`](#b-esportare-e-condividere-linvito-mcsync)
   - [C. Importare l'Invito (Amici)](#c-importare-linvito-amici)
   - [D. Giocare in 1 Clic (Host o Client Automatico)](#d-giocare-in-1-clic-host-o-client-automatico)
   - [E. Aggiornare il Modpack e Fare Rollback](#e-aggiornare-il-modpack-e-fare-rollback)
4. [Guida ai Comandi CLI (Terminale / Headless)](#4-guida-ai-comandi-cli-terminale--headless)
5. [Architettura Repository, Lockfile e Cache Condivisa](#5-architettura-repository-lockfile-e-cache-condivisa)
6. [Migrazione Automatica da v1.1](#6-migrazione-automatica-da-v11)
7. [Risoluzione Problemi (Troubleshooting & Centro Diagnostico)](#7-risoluzione-problemi-troubleshooting--centro-diagnostico)

---

## 1. Novità Principali di `mcServer4all` v2.0

* **Mondi Multipli nella Stessa Repository (`worlds/<worldId>/`)**: puoi avere più mondi diversi (es. `stoneblock3`, `vanilla-plus`, `cobblemon`) sullo stesso repository Git privato o bucket S3. Il lock di un mondo non blocca gli altri.
* **Modpack Bloccato (`mods.lock.json`) e Modalità `LOCKED`**: una sola fonte di verità per ogni mondo. Spariscono le scansioni lente ad ogni avvio e i conflitti *"vince l'host"*: ad ogni avvio si confronta un unico hash con lo stato locale (`LocalState`).
* **Installazione Automatica e Idempotente del Server (`ServerInstaller`)**: il Server Pack non viene ridistribuito; nella repo resta la ricetta (`world.json`). Qualsiasi amico che diventi Host costruisce automaticamente lo stesso identico server (Forge, NeoForge, Fabric, Quilt) con OpenJDK 21 portatile.
* **Istanze Prism Launcher per Mondo (`mcsync-<worldId>`)**: create e mantenute in automatico da `mcServer4all` a partire dal lockfile, senza dover configurare percorsi a mano.
* **Cache Locale Condivisa (`.mcsync/cache/modfiles/<sha256>`)**: i `.jar` delle mod vengono scaricati in parallelo dai CDN ufficiali (Modrinth / CurseForge, con fallback blob nel repo) e collegati tramite *hard-link* tra tutti i mondi senza duplicare spazio su disco.
* **Inviti `.mcsync` in 1 Clic**: un singolo file JSON contiene coordinate del mondo, versione del lock, checksum di sicurezza `lockSha256` e credenziali del repository.
* **Aggiornamento Modpack Sicuro & Rollback**: chiunque del gruppo può aggiornare il modpack (solo a mondo libero), visualizzare il **diff** (`[+]`, `[-]`, `[~]`), generare un backup automatico pre-aggiornamento, pubblicare con scrittura condizionale (`putIfMatch`) o tornare alla versione precedente con un clic.

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

### Passo 3 — Configurazione Iniziale (`Configurazione`)
Apri la scheda **Configurazione** nella GUI di `mcServer4all`:
1. Imposta il tuo **`playerId`** (es. il tuo nickname Minecraft).
2. **Solo per chi crea il primo mondo** (gli amici riceveranno tutto tramite il file d'invito `.mcsync`!):
   * Crea una repository **Privata** su GitHub (es. `https://github.com/tuo-utente/mc-worlds.git`).
   * Crea un **Fine-grained Personal Access Token** limitato a quella sola repository con permesso **`Contents: Read and write`**.
   * Inserisci `gitRemoteUrl` e `gitToken` nella scheda **Configurazione**, clicca **"Testa Connessione"** e poi **"SALVA CONFIGURAZIONE"**.

---

## 3. Flusso d'Uso Completo (GUI Desktop)

L'interfaccia grafica di `mcServer4all` è divisa in **5 schede**:
* **Dashboard**: stato live del mondo attivo (semafori Tailscale, Lock e Modpack) e pulsante unico di avvio.
* **Monitoraggio Server**: utilizzo RAM/CPU in tempo reale, lista giocatori online e console interattiva del server.
* **Configurazione**: impostazioni giocatore, Tailscale, GitHub/S3 e RAM.
* **Log & AI**: centro diagnostico con spiegazione degli errori e riparazione automatica a 1 clic.
* **Mondi**: gestione completa di tutti i mondi condivisi, inviti `.mcsync`, creazione, aggiornamento modpack e rollback.

---

### A. Creare un Nuovo Mondo (Host Iniziale)

1. Apri la scheda **Mondi** (oppure clicca **"Mondi & Inviti (.mcsync)"** dalla Dashboard) e premi **"Crea Mondo..."**.
2. Compila i campi del wizard:
   * **Nome Visualizzato**: es. `StoneBlock 3 con gli amici`.
   * **ID Mondo (slug)**: es. `stoneblock3` (usato per la cartella `worlds/stoneblock3/` nel repo e per l'istanza Prism `mcsync-stoneblock3`).
   * **Sorgente Modpack Client** (4 formati supportati):
     1. **Archivio Modrinth (`.mrpack`)**: legge `modrinth.index.json`, gli URL CDN ufficiali e i lati client/server.
     2. **Archivio CurseForge (`.zip`)**: legge `manifest.json` + `overrides/` (richiede `curseforgeApiKey` in `config.json` o variabile d'ambiente `MCSYNC_CURSEFORGE_API_KEY` se i `.jar` non sono già in `overrides/`).
     3. **Istanza Prism Launcher esistente**: il metodo più rapido se hai già provato il modpack in Prism Launcher; legge `mmc-pack.json`, `mods/` e `config/`.
     4. **Cartella manuale**: una cartella con `mods/`, `config/` e `mmc-pack.json`.
   * **Server Pack (Opzionale)**: se il modpack ha un Server Pack `.zip` o cartella separata, selezionalo qui. `mcServer4all` confronterà automaticamente i `.jar` per distinguere le mod `both`, `server` e `client` (es. *Sodium, Iris, Oculus, Embeddium* restano solo sul client).
   * **Risorse Server**: imposta RAM Min/Max (MB) e porta (default `25565`).
   * Lascia attiva la spunta **"Esporta subito il file d'invito (.mcsync) dopo la creazione"**.
3. Premi **OK**: `mcServer4all` genererà `world.json` e `mods.lock.json`, installerà il server locale, creerà l'istanza Prism `mcsync-<worldId>` e pubblicherà il mondo nel repository.

---

### B. Esportare e Condividere l'Invito `.mcsync`

* Se non l'hai già esportato durante la creazione, nella scheda **Mondi** seleziona il mondo e clicca **"Esporta Invito (.mcsync)..."**.
* Otterrai un piccolo file JSON (es. `stoneblock3.mcsync`).
* Invia il file `.mcsync` ai tuoi amici su una chat privata (es. messaggio diretto Discord).

> **Nota di sicurezza sul token**: per azzerare la configurazione manuale degli amici, il file `.mcsync` include il token del repository. Non pubblicarlo in chat o repository pubbliche; usa sempre un *fine-grained token* GitHub limitato alla sola repository dei mondi.

---

### C. Importare l'Invito (Amici)

Chi riceve il file `stoneblock3.mcsync`:
1. Apre `mcServer4all`, va nella scheda **Mondi** e clicca **"Importa Invito (.mcsync)..."**.
2. Seleziona il file `.mcsync`.
3. `mcServer4all` verifica la firma `lockSha256`, registra il mondo e le credenziali del repository, scarica le mod nella cache locale condivisa e crea automaticamente l'istanza Prism **`mcsync-stoneblock3`**.
4. Il mondo appare subito nella lista **"Unisciti al mondo StoneBlock 3 con gli amici"**!

---

### D. Giocare in 1 Clic (Host o Client Automatico)

1. Nella scheda **Mondi**, seleziona il mondo con cui vuoi giocare e premi **"GIOCA NEL MONDO SELEZIONATO (1 CLIC)"** (oppure premi il pulsante principale nella **Dashboard**).
2. `mcServer4all` controlla lo stato del mondo in tempo reale:
   * **Se il mondo è LIBERO**: acquisisce il lock atomico, scarica solo il delta dei file modificati nell'ultima sessione, verifica che il server locale corrisponda alla ricetta `world.json` + `mods.lock.json` (costruendolo da solo se è la prima volta che fai da Host!), avvia il server e apre automaticamente il tuo client Minecraft.
   * **Se un amico sta già ospitando (`ONLINE`)**: verifica in un istante che l'istanza Prism `mcsync-<worldId>` sia allineata al lockfile (scaricando l'eventuale differenziale se il modpack è stato aggiornato) e lancia Minecraft collegandosi direttamente al server dell'amico.
3. **A fine partita**: quando l'Host chiude il server (o dopo 60 secondi da quando l'ultimo giocatore si è disconnesso), il mondo viene compresso, solo i file modificati nella sessione vengono caricati sulla repo e il lock viene rilasciato.

---

### E. Aggiornare il Modpack e Fare Rollback

Qualsiasi membro del gruppo può aggiornare il modpack del mondo in modo sicuro:

1. Assicurati che **nessuno stia ospitando** il mondo (se il mondo è in uso, `mcServer4all` rifiuterà l'operazione avvisandoti chi sta giocando).
2. Nella scheda **Mondi**, seleziona il mondo e clicca **"Aggiorna Modpack..."**.
3. Scegli se leggere le modifiche:
   * **Dalla tua istanza Prism del mondo (`mcsync-<worldId>`)** (dopo aver aggiunto/rimosso/aggiornato mod dentro Prism), oppure
   * **Da un nuovo file `.zip` / `.mrpack`**.
4. `mcServer4all` calcola il nuovo lockfile e ti mostra una **finestra di riepilogo con il Diff completo**:
   * `[+]` mod aggiunte
   * `[-]` mod rimosse (con avviso esplicito)
   * `[~]` mod aggiornate di versione
5. Confermando:
   * Viene creato un **backup automatico pre-aggiornamento** del mondo (`pre-update-backups/` e `backups/`).
   * La versione precedente del lock viene salvata in `worlds/<id>/locks/v<N>.json`.
   * Il nuovo `mods.lock.json` viene pubblicato con scrittura condizionale (`putIfMatch`) incrementando `modpackVersion`.
6. **Rollback in 1 clic**: se dopo un aggiornamento il modpack dà problemi, clicca **"Rollback Modpack..."**, scegli la versione precedente (es. `v1`) e premi OK.

---

## 4. Guida ai Comandi CLI (Terminale / Headless)

Tutte le funzioni della GUI sono disponibili anche da riga di comando tramite `launch.bat` (Windows) o `./launch.sh` (Linux/macOS):

```bat
:: --- Avvio e Gioco ---
launch.bat                                :: Apre l'interfaccia grafica (GUI)
launch.bat --no-gui                       :: Avvia la sessione (Host o Client) sul mondo attivo da terminale
launch.bat join <worldId>                 :: Seleziona <worldId> e avvia subito la sessione
launch.bat join-file invito.mcsync        :: Importa un file d'invito .mcsync e configura il mondo
launch.bat join-file invito.mcsync --run  :: Importa l'invito e avvia subito il gioco
launch.bat --no-gui --no-client           :: Modalità server dedicato headless (senza avviare il client)

:: --- Gestione Mondi ---
launch.bat world list                     :: Elenca i mondi registrati con stato live e versione modpack
launch.bat world create stoneblock3 --client-pack pack.mrpack --server-pack srv.zip --name "StoneBlock 3" --export-invite
launch.bat world select <worldId>         :: Imposta il mondo predefinito
launch.bat world export <worldId> [file]  :: Esporta il file d'invito <worldId>.mcsync
launch.bat world import <invito.mcsync>   :: Importa un file d'invito .mcsync
launch.bat world remove <worldId>         :: Rimuove il mondo da questo PC (--delete-files per eliminare anche i file locali)

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
launch.bat migrate                        :: Esegue la migrazione manuale da v1.1 a v2.0
launch.bat migrate-rollback               :: Annulla la migrazione v1.1 ripristinando il backup
```

---

## 5. Architettura Repository, Lockfile e Cache Condivisa

Nella repository Git (branch `mcsync-state`) o nel bucket S3, `mcServer4all` mantiene una struttura pulita e separata per ogni mondo:

```text
<repo>/                              (branch: mcsync-state)
├── registry.json                    # Indice dei mondi presenti nel repository
└── worlds/
    ├── stoneblock3/
    │   ├── world.json               # Ricetta server: MC, loader, versione loader, Java, JVM args, porta, whitelist
    │   ├── mods.lock.json           # Lockfile del modpack (versione corrente, hash SHA-256, side, sorgenti CDN/blob)
    │   ├── locks/                   # Versioni precedenti di mods.lock.json per il rollback (v1.json, v2.json...)
    │   ├── lock.json                # Lock atomico dell'host attivo per QUESTO mondo
    │   ├── manifest.json            # Versione mondo + indice file per il sync differenziale
    │   ├── modfiles/<sha256>        # Store content-addressed per jar senza CDN pubblico e config bloccate
    │   ├── world_blobs/<sha256>.zst # Blob compressi Zstd del salvataggio del mondo
    │   └── backups/                 # Backup periodici e backup pre-aggiornamento modpack
    └── vanilla-plus/
        └── ...stessa struttura indipendente
```

### Come vengono gestite le Configurazioni (`config/`)
1. **Config del Server (`serverFiles`)**: incluse nel lock e materializzate sul server (es. `kubejs/`, `defaultconfigs/`).
2. **Config personali del Client** (`options.txt`, keybind, shader, audio): **non vengono mai sovrascritte**.
3. **Config bloccate (`pinnedConfigs`)**: file che client e server devono avere identici ma che il loader non invia al login (es. `config/**/*-common.toml`, `kubejs/**`). Vengono verificate e allineate quando cambia `modpackVersion`.

---

## 6. Migrazione Automatica da v1.1

Se avevi già configurato una versione `1.1.x`, il passaggio a `mcServer4all v2.0` è **automatico e reversibile**:
1. I campi singoli di `config.json` vengono migrati in un mondo predefinito chiamato **`default`** (salvando una copia in `config.json.v1.1.backup.json`).
2. Tutte le chiavi presenti alla radice del repository vengono copiate di sicurezza in `migration_v1_backup/` e spostate sotto `worlds/default/`.
3. A partire da `mods_manifest.json` e `client_mods_manifest.json` vengono generati automaticamente `worlds/default/world.json` e `worlds/default/mods.lock.json`.
4. Finché il backup non viene eliminato, puoi tornare alla struttura v1.1 in qualsiasi momento eseguendo `launch.bat migrate-rollback`.

---

## 7. Risoluzione Problemi (Troubleshooting & Centro Diagnostico)

In caso di problemi, apri la scheda **`Log & AI`** nella GUI: l'analizzatore euristico ([`LogAnalyzer`](file:///home/pietro/Desktop/code/mcServer4All/src/main/java/com/mcsync/diagnostics/LogAnalyzer.java)) individua l'errore per il mondo attivo e offre un pulsante **"🔧 Applica Fix Automatico"**.

| Problema / Messaggio | Causa | Soluzione |
|---|---|---|
| **`Connection timed out: getsockopt`** | Il server è in ascolto sull'IP Tailscale ma il client locale punta a `127.0.0.1`. | Gestito automaticamente da `effectiveClientAddress()`; clicca **"Allinea IP Client a Tailscale"** in *Log & AI*. |
| **`player_X sta ospitando il mondo: riprova a fine sessione`** | Hai provato ad aggiornare il modpack mentre un amico sta giocando in quel mondo. | Attendi che la sessione su quel mondo termini e che il lock torni `LIBERO`. |
| **`Il modpack richiede una API key CurseForge`** | Lo `.zip` CurseForge contiene riferimenti a file non inclusi in `overrides/`. | Imposta `"curseforgeApiKey"` in `config.json` oppure importa prima il pack in Prism Launcher e scegli **"Istanza Prism esistente"** nel wizard *Crea Mondo*. |
| **`Il modpack è stato modificato da qualcun altro nel frattempo`** | Due giocatori hanno tentato di pubblicare un aggiornamento modpack nello stesso istante (`putIfMatch`). | Riapri *Aggiorna Modpack*: il launcher ripartirà automaticamente dalla nuova versione appena pubblicata. |
| **Sessione bloccata (`STALE LOCK`) dopo un crash del PC Host** | L'Host si è spento senza rilasciare `lock.json`. | Dopo `lockTimeoutSeconds` (120s) il launcher subentra da solo; per sbloccare subito premi **"Sblocca Sessione (Force Unlock)"** in *Log & AI*. |

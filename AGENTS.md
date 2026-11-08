# AGENTS.md — Contesto Condiviso per Antigravity (mcServer4all v2.0)

Questo file fornisce il contesto architetturale, operativo e le regole fondamentali per qualsiasi agente AI (**Antigravity CLI / IDE**) che operi su questa repository, garantendo continuità tra diverse macchine.

---

## 1. Panoramica del Progetto

**`mcServer4all`** (v2.0) è una piattaforma peer-to-peer che consente a un gruppo di amici di condividere **uno o più mondi Minecraft moddati** (Forge, NeoForge, Fabric, Quilt, CurseForge, Modrinth, FTB) senza pagare un server dedicato 24/7.
- **Rete P2P**: Usa **Tailscale** per connettere i giocatori direttamente all'Host attivo sulla porta `25565`.
- **Ruolo Dinamico (Host / Client)**: Chi avvia per primo un mondo libero ne diventa **Host** (acquisendo il lock atomico `lock.json`, costruendo/avviando il server locale e sincronizzando i delta dei salvataggi a fine sessione). Se qualcuno sta già ospitando, gli altri entrano automaticamente come **Client**.
- **Storage Remoto dei Mondi**: I mondi, i lockfile (`mods.lock.json`) e i blob Zstd (`world_blobs/`) vengono salvati su un repository Git privato dedicato (branch `mcsync-state`) o bucket S3.

---

## 2. Scopo di Questa Repository (`Tungsteno-Code/mcServer4All`)

Questa repository (`branch: main`) è il **pacchetto di distribuzione ed esecuzione** del client/launcher `mcServer4all`.
Viene utilizzata su tutte le macchine dei giocatori per:
1. Avviare `mcServer4all` tramite `launch.bat` (Windows) o `./launch.sh` (Linux/macOS).
2. Ricevere aggiornamenti di versione semplicemente eseguendo **`git pull`**.

### File Tracciati nel Repository (Versionati)
- `mcServer4all.jar`: Eseguibile Java compilato (include GUI Desktop e CLI).
- `launch.bat` / `launch.sh`: Script di bootstrap che verificano/scaricano automaticamente OpenJDK 21 portatile in `.tools/jdk-21` e avviano il jar.
- `config.example.json`: Template di configurazione (senza dati sensibili).
- `README.md`: Documentazione utente completa (GUI, CLI, architettura, troubleshooting).
- `AGENTS.md`: Questo file di contesto per gli agenti Antigravity.
- `.gitignore`: Regole di esclusione per proteggere dati utente e mondi locali.

### File e Cartelle Esclusi da Git (NON Tracciare Mai!)
Per garantire che `git pull` funzioni sempre senza conflitti e senza esporre segreti, **non aggiungere né committare mai**:
- `config.json`: Contiene `gitToken` personali, `curseforgeApiKey`, `playerId` e percorsi assoluti locali della macchina.
- `servers/`: Contiene le installazioni dei server Minecraft (`servers/<worldId>/`), i salvataggi dei mondi, i log e i backup locali.
- `.tools/`: Contiene `jdk-21` e `PrismLauncher` portatili (centinaia di MB gestiti in automatico).
- `.mcsync/` e `*.mcsync`: Cache condivisa dei file `.jar` delle mod (`.mcsync/cache/modfiles/<sha256>`) e file d'invito (che includono token di accesso).

---

## 3. Comandi Principali e Operatività CLI

Quando operi da terminale su una macchina, usa sempre `launch.bat` (su Windows) o `./launch.sh` (su Linux/macOS):

```bat
:: Avvio GUI
launch.bat

:: Diagnostica e Setup Ambiente (Java 21, Git, Prism, Tailscale, Firewall)
launch.bat setup
launch.bat status

:: Gestione Mondi da CLI
launch.bat world list
launch.bat world select <worldId>
launch.bat world import <file.mcsync>
launch.bat world export <worldId>

:: Avvio Sessione da Terminale
launch.bat join <worldId>
launch.bat --no-gui
launch.bat --no-gui --no-client    :: Solo server headless

:: Gestione Lock e Emergenze
launch.bat force-unlock            :: Sblocca un lock rimasto appeso dopo crash dell'Host
launch.bat list-backups
launch.bat restore <backupKey>
```

---

## 4. Struttura Locale Generata a Runtime

Su ogni macchina in cui gira `mcServer4all`, la struttura locale (ignorata da Git) è:
- `.mcsync/cache/modfiles/<sha256>`: Cache content-addressed delle mod; i `.jar` vengono collegati tramite *hard-link* nelle cartelle `servers/<worldId>/mods` e nelle istanze Prism per non duplicare spazio.
- `.tools/jdk-21/`: Eclipse Temurin OpenJDK 21 scaricato al primo avvio.
- `.tools/PrismLauncher/`: Istanza portatile di Prism Launcher con le istanze gestite `mcsync-<worldId>`.
- `servers/<worldId>/`: Directory di lavoro del server Minecraft per ciascun mondo, costruita in modo idempotente da `ServerInstaller` leggendo `world.json` e `mods.lock.json`.

---

## 5. Linee Guida per l'Agente Antigravity

1. **Lingua**: Rispondi sempre in **Italiano**.
2. **Sicurezza Credenziali**: Non stampare in chiaro né committare mai i token presenti in `config.json` o nei file `*.mcsync`.
3. **Aggiornamenti di Versione**:
   - Per aggiornare una macchina client: esegui `git pull`.
   - Per rilasciare una nuova versione del launcher: sostituisci `mcServer4all.jar` (ed eventualmente `launch.bat`/`launch.sh`/`README.md`), verifica con `git status` che nessun file locale o segreto sia staged, fai commit e `git push`.
4. **Risoluzione Problemi Modpack / Server**:
   - Prima di modificare manualmente i file dentro `servers/<worldId>/`, ricorda che `mcServer4all` opera in modalità `LOCKED` basata su `mods.lock.json` e `world.json`.
   - Controlla sempre i log in `servers/<worldId>/logs/latest.log` o `crash-reports/` in caso di crash del server Minecraft.

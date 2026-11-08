# AGENTS.md — Contesto Condiviso per Antigravity (mcServer4all v3.0)

Questo file fornisce il contesto architetturale, operativo e le regole fondamentali per qualsiasi agente AI (**Antigravity CLI / IDE**) che operi su questa repository, garantendo continuità tra diverse macchine.

---

## 1. Panoramica del Progetto

**`mcServer4all`** (v3.0 — *Paradigma Single-JVM & Pack-Native*) è una piattaforma peer-to-peer che consente a un gruppo di amici di condividere **uno o più mondi Minecraft moddati** (Forge, NeoForge, Fabric, Quilt, CurseForge, Modrinth, FTB) senza pagare un server dedicato 24/7 e dimezzando il consumo di RAM.
- **Rete P2P**: Usa **Tailscale** per connettere i giocatori direttamente all'Host attivo sulla porta `25565` (senza necessità di gestire manualmente la Whitelist).
- **Single-JVM Client-as-Server (`IntegratedHostController`)**: Chi avvia per primo un mondo libero ne diventa **Host** in una **singola JVM** (il mondo viene eseguito nell'Integrated Server del client Prism Launcher con `pauseOnLostFocus:false`, apertura LAN automatica e bridge TCP su Tailscale `25565`, risparmiando il 50% di RAM). Supporta anche la modalità Server Dedicato Headless con `--no-client`.
- **Fast Handover & Sanitizzazione `level.dat` (`LevelDatCleaner`)**: Tornando al Menu Principale di Minecraft il mondo viene sincronizzato e il lock rilasciato senza chiudere il client; il tag `Data.Player` viene rimosso da `level.dat` ad ogni sync per evitare conflitti di inventario tra Host diversi.
- **Pack-Native Sync & CurseForge Batch API**: Le mod vengono risolte in batch (`POST /v1/mods/files` e Murmur2 `POST /v1/fingerprints/432` con fallback `edge.forgecdn.net`) e scaricate su 8 thread paralleli; le cartelle `overrides/` vengono compresse in volumi `overrides.partXX.tar.zst` (< 75 MB) con protezione *Seed-Only* delle preferenze client (`options.txt`, `*-client.toml`).
- **Storage Remoto Unificato**: Tutti i mondi condividono un'unica working tree Git locale in `.mcsync/git-store-<urlHash>` sincronizzata sul branch orphan `mcsync-state` (o bucket S3).

---

## 2. Scopo di Questa Repository (`Tungsteno-Code/mcServer4All`)

Questa repository (`branch: main`) contiene sia il **pacchetto di distribuzione eseguibile** (`mcServer4all.jar`, `launch.bat`, `launch.sh`) sia il **codice sorgente completo** (`src/`, `pom.xml`, `ARCHITETTURA_NUOVO_PARADIGMA.md`).
Viene utilizzata su tutte le macchine dei giocatori per:
1. Avviare `mcServer4all` tramite `launch.bat` (Windows) o `./launch.sh` (Linux/macOS).
2. Ricevere aggiornamenti di versione semplicemente eseguendo **`git pull`**.

### File Tracciati nel Repository (Versionati)
- `mcServer4all.jar`: Eseguibile Java compilato (include GUI Desktop animata a 60 FPS e CLI).
- `launch.bat` / `launch.sh`: Script di bootstrap che verificano/scaricano automaticamente OpenJDK 21 portatile in `.tools/jdk-21` e avviano il jar.
- `config.example.json`: Template di configurazione (senza dati sensibili).
- `README.md`: Documentazione utente completa (GUI v3.0, CLI, architettura, troubleshooting).
- `ARCHITETTURA_NUOVO_PARADIGMA.md`: Specifica tecnica dettagliata dei 3 Pilastri dell'architettura v3.0.
- `AGENTS.md`: Questo file di contesto per gli agenti Antigravity.
- `src/` & `pom.xml`: Codice sorgente Java 21 e configurazione di build Maven.
- `.gitignore`: Regole di esclusione per proteggere dati utente, build intermedie e mondi locali.

### File e Cartelle Esclusi da Git (NON Tracciare Mai!)
Per garantire che `git pull` funzioni sempre senza conflitti e senza esporre segreti, **non aggiungere né committare mai**:
- `config.json`: Contiene `gitToken` personali, `curseforgeApiKey`, `playerId` e percorsi assoluti locali della macchina.
- `servers/`: Contiene le installazioni dei server Minecraft (`servers/<worldId>/`), i salvataggi dei mondi, i log e i backup locali.
- `.tools/`: Contiene `jdk-21` e `PrismLauncher` portatili (centinaia di MB gestiti in automatico).
- `.mcsync/` e `*.mcsync`: Checkout Git condiviso (`.mcsync/git-store-*`), cache content-addressed delle mod (`.mcsync/cache/modfiles/<sha256>`) e file d'invito (che includono token di accesso e API key).
- `target/` e `dist/`: Artefatti intermedi di compilazione Maven.

---

## 3. Comandi Principali e Operatività CLI

Quando operi da terminale su una macchina, usa sempre `launch.bat` (su Windows) o `./launch.sh` (su Linux/macOS):

```bat
:: Avvio GUI Animata (3 schede: Mondi & Avvio, Impostazioni, Diagnostica)
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
launch.bat --no-gui --no-client    :: Solo server dedicato headless (senza client)

:: Gestione Lock e Emergenze
launch.bat force-unlock            :: Sblocca un lock rimasto appeso dopo crash dell'Host
launch.bat list-backups
launch.bat restore <backupKey>
```

---

## 4. Struttura Locale Generata a Runtime

Su ogni macchina in cui gira `mcServer4all`, la struttura locale (ignorata da Git) è:
- `.mcsync/git-store-<urlHash>/`: Checkout Git unico condiviso tra tutti i mondi che puntano allo stesso repository remoto.
- `.mcsync/cache/modfiles/<sha256>`: Cache content-addressed delle mod; i `.jar` vengono collegati tramite *hard-link* nelle istanze Prism (e in `servers/<worldId>/mods` se in modalità headless) per non duplicare spazio.
- `.tools/jdk-21/`: Eclipse Temurin OpenJDK 21 scaricato al primo avvio.
- `.tools/PrismLauncher/`: Istanza portatile di Prism Launcher con le istanze gestite `mcsync-<worldId>`.
- `servers/<worldId>/`: Directory di stato e staging del salvataggio del mondo (`servers/<worldId>/world`).

---

## 5. Linee Guida per l'Agente Antigravity

1. **Lingua**: Rispondi sempre in **Italiano**.
2. **Sicurezza Credenziali**: Non stampare in chiaro né committare mai i token presenti in `config.json` o nei file `*.mcsync`.
3. **Aggiornamenti di Versione**:
   - Per aggiornare una macchina client: esegui `git pull`.
   - Per rilasciare una nuova versione del launcher: compila con `mvn test package`, copia `target/mcServer4all.jar` in `./mcServer4all.jar`, verifica con `git status` che nessun file locale o segreto sia staged, fai commit e `git push`.
4. **Risoluzione Problemi Modpack / Mondo**:
   - Ricorda che `mcServer4all` opera in modalità `LOCKED` basata su `mods.lock.json` (schema v2 con `overridesBundles`) e `world.json`.
   - Controlla sempre i log dell'istanza Prism o di `servers/<worldId>/logs/latest.log` in caso di crash di Minecraft.

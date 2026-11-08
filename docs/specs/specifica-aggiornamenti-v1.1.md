# Specifiche di Progetto: Nuove Funzionalità e Ottimizzazioni per mc-sync-launcher

> **Destinatario**: Antigravity (AI Coding Assistant / Pair Programmer)  
> **Obiettivo**: Implementare una GUI moderna e intuitiva, ottimizzare la pipeline di sincronizzazione per i client, aggiungere la diagnostica interattiva dei log e aggiornare radicalmente la documentazione (`README.md`).

---

# PARTE 1: SEZIONE AGGIORNAMENTO (Ottimizzazioni & Miglioramenti Esistenti)

### 1.1 Ottimizzazione del Sync Mod/Config (Risoluzione overhead `.toml`)
* **Problema Attuale**:
  Quando un giocatore si collega in modalità **Client**, il launcher scansiona e scarica tutti i file di configurazione (`.toml`, `.json`, `.cfg`, `.snbt`) presenti nel manifest remoto. Su modpack complessi come *FTB StoneBlock 3*, si tratta di centinaia di file `.toml` che:
  1. Non sono necessari al client (molti sono file `serverconfig` o logiche solo server).
  2. Forge / Fabric sincronizzano già nativamente le configurazioni server critiche via rete al momento del login (`serverconfig` del mondo).
  3. Rallentano drasticamente i tempi di connessione del client e possono sovrascrivere impostazioni grafiche o di accessibilità locali del giocatore (es. keybind, shader, volumi audio).
* **Soluzione Tecnica da Implementare**:
  1. **Modalità di sincronizzazione differenziata Client vs Host**:
     * In modalità **Client**: sincronizzare **esclusivamente la cartella `mods/` (file `.jar`)** e le sole configurazioni strettamente necessarie (`client-sync-safe`).
     * In modalità **Host**: eseguire la sincronizzazione completa sia delle mod sia del mondo (`world/`).
  2. **Introduzione di una policy configurabile in `config.json`**:
     * Aggiungere l'opzione `"clientSyncScope": "MODS_ONLY"` (valori ammessi: `"MODS_ONLY"`, `"ALL"`, default consigliato: `"MODS_ONLY"`).
     * Se impostato su `"MODS_ONLY"`, il client verifica solo l'hash dei `.jar` in `mods/`.
  3. **Fingerprint & Quick-Hash Cache**:
     * Memorizzare l'ultimo SHA del manifest remoto sincronizzato in `.mcsync/state.json`.
     * Se l'hash remoto non è cambiato rispetto all'ultimo avvio, saltare del tutto il check locale file-per-file (avvio istantaneo in < 1 secondo).

### 1.2 Risoluzione Automatica del bug `Connection timed out: getsockopt` (Tailscale Loopback)
* **Problema Riscontrato**:
  Su Windows, quando `ServerProcessController` compila `server.properties`, imposta `server-ip=<TailscaleIP>` (es. `100.118.202.43`). Il server Minecraft si lega (*bind*) esclusivamente a quell'interfaccia di rete. Se in `config.json` è impostato `"localClientAddress": "127.0.0.1"`, il client locale tenta di connettersi a `127.0.0.1:25565` e fallisce con `Connection timed out: getsockopt` perché la porta non è in ascolto sul loopback.
* **Soluzione Tecnica da Implementare**:
  1. Nel caricamento e validazione di `Config`:
     * Se `"localClientAddress"` è impostato su `"auto"`, `"127.0.0.1"` o vuoto, e `localTailscaleIp` è valorizzato con un IP Tailscale reale, risolvere dinamicamente `localClientAddress` facendolo combaciare con `localTailscaleIp` (o lasciare vuoto `server-ip` in `server.properties` se non ci sono conflitti di multi-homing).
  2. In `prepareServerProperties()`:
     * Supportare la modalità `server-ip=""` (bind a `0.0.0.0`) come opzione consentita, oppure garantire che `{serverAddress}` per il client locale usi sempre l'IP esatto a cui il server ha effettuato il bind.

### 1.3 Prevenzione degli Errori di Avvio del Server (`run.bat` / Forge / Java)
* **Problema Riscontrato**:
  Gli utenti tendono a inserire `run.bat` nel campo `serverStartCommand` preceduto da `java -jar`, provocando il crash `Error: Invalid or corrupt jarfile run.bat`. Inoltre, su modpack Forge moderni, la memoria è definita in `user_jvm_args.txt`.
* **Soluzione Tecnica da Implementare**:
  1. **Auto-Detection avanzata del tipo di server**:
     * Se nella cartella `serverDirectory` è presente `run.bat` (o `run.sh` su Linux), impostare automaticamente il comando di avvio a `["run.bat"]` senza parametri `java`.
     * Riconoscere automaticamente i profili server:
       - **Forge / NeoForge moderno**: rileva `run.bat` + `user_jvm_args.txt` + `libraries/`.
       - **Fabric / Quilt**: rileva `fabric-server-launch.jar` o `server.jar`.
       - **CurseForge / FTB Pack**: rileva script di installazione non ancora eseguiti e notifica l'utente con un pulsante *Esegui Installazione Server Pack*.
  2. **Validazione pre-volo in `ServerProcessController`**:
     * Prima di avviare il processo, verificare che il file specificato esista e che non si stia passando un file `.bat`/`.sh` a `java.exe`. Se viene rilevato l'errore, correggerlo automaticamente al volo.

### 1.4 Aggiornamento radicale del `README.md`
Il file `README.md` deve essere aggiornato includendo sezioni chiare ed esplicative:
1. **Guida Rapida all'Avvio (Quick Start)** con e senza GUI.
2. **Guida Completa all'installazione di un Modpack (Esempio pratico: FTB StoneBlock 3)**:
   * Come installare il Server Pack nella cartella dedicata.
   * Come eseguire l'installer di Forge/FTB per generare `libraries/` e `run.bat`.
   * Come importare il modpack client su Prism Launcher e associarlo al launcher.
3. **Troubleshooting Guide (Risoluzione Problemi Comuni)**:
   * *Errore `getsockopt`*: spiegazione del bind di rete e configurazione IP.
   * *Errore `Invalid or corrupt jarfile run.bat`*: spiegazione tra script batch ed eseguibili java jar.
   * *Firewall di Windows*: sblocco delle porte su reti Private e Pubbliche.
   * *Lock stale / crash improvvisi*: come usare `force-unlock` o la GUI per recuperare il mondo.

---

# PARTE 2: SEZIONE AGGIUNTE (Nuove Funzionalità & GUI Completa)

### 2.1 Architettura della Nuova GUI
* **Framework**: Java Swing moderno con look-and-feel **FlatLaf** (Dark / Light theme, stile moderno in stile IntelliJ / VS Code). Non richiede runtime pesanti esterni ed è compatibile nativamente con Java 21 senza problemi di moduli.
* **Modalità Duale (GUI + Headless CLI)**:
  * Se avviato senza argomenti o con `launch.bat`, si apre di default la **GUI Desktop**.
  * Se avviato con flag come `--no-gui`, `status`, `import-curseforge`, il launcher funziona in modalità testuale/CLI per retrocompatibilità e server remoti.

---

### 2.2 Schermate e Funzionalità della GUI

```
+-----------------------------------------------------------------------------------+
|  [mc-sync-launcher v1.1.0] - Modded World Orchestrator              [-] [o] [x]  |
+-----------------------------------------------------------------------------------+
|  [ Dashboard ]  |  [ Monitoraggio Server ]  |  [ Configurazione ]  |  [ Log & AI ]|
+-----------------------------------------------------------------------------------+
|                                                                                   |
|  STATO DEL MONDO CONDIVISO                                                       |
|  +-------------------------------------+  +------------------------------------+  |
|  | Lock Git:   LIBERO                  |  | Ruolo:         Pronto ad Hostare   |  |
|  | Versione:   v1 (Ultimo sync: 12:40) |  | Tailscale IP:  100.118.202.43      |  |
|  | Giocatore:  Domeiannetta            |  | Modpack:       FTB StoneBlock 3    |  |
|  +-------------------------------------+  +------------------------------------+  |
|                                                                                   |
|  [ AVVIA SESSIONE (HOST / JOIN AUTOMATICO) ]   [ SINCRONIZZA MOD ]  [ REFRESH ]   |
|                                                                                   |
+-----------------------------------------------------------------------------------+
```

#### Schermata 1: Dashboard Principale (Connessione & Stato)
* **Indicatori Visivi a Semaforo**:
  * Stato Rete Tailscale (Verde = Connesso con IP visibile, Rosso = Disconnesso con link per aprire l'app).
  * Stato Lock Git (Verde = Libero, Blu = Sei l'Host attuale, Arancione = Hostato da un amico es. *TungstenoTrue*).
  * Stato Modpack & File (Verde = Allineato, Giallo = Aggiornamenti disponibili).
* **Pulsante Unico Intelligente ("Avvia")**:
  * Rileva in automatico se c'è già un Host online:
    * Se il mondo è libero: diventa **"Avvia come Host"** (accende server + avvia Prism client locale).
    * Se l'amico sta già hostando: diventa **"Entra nel Server"** (sync rapido mod + avvia Prism client collegato all'IP dell'host).

#### Schermata 2: Pannello di Monitoraggio Server & Risorse (Live Dashboard)
* Attivo sia durante l'Hosting locale, sia come stato informativo per il client:
  * **Grafico Risorse in Tempo Reale**:
    * Utilizzo RAM Server (MB Usati / MB Allocati / Picco massimo).
    * Utilizzo CPU del processo server.
  * **Giocatori Online**:
    * Lista dei nickname attualmente connessi nel server.
    * Tempo trascorso dall'ultimo giocatore online (e conto alla rovescia di autospegnimento a 60s per il risparmio risorse).
  * **Console Interattiva per l'Host**:
    * Visualizzazione dei log del server in tempo reale con evidenziazione sintattica (INFO in grigio/bianco, WARN in giallo, ERROR in rosso, JOIN/LEAVE in verde).
    * **Barra di input comandi**: permette all'Host di digitare qualsiasi comando Minecraft (es. `/op <player>`, `/whitelist add <player>`, `/time set day`, `/save-all`, `stop`) direttamente dalla finestra della GUI, senza dover accedere a finestre terminale esterne.

#### Schermata 3: Editor di Configurazione Visuale (`config.json`)
Consente di configurare l'intero applicativo senza dover toccare manualmente file JSON:
* **Generale**:
  * Nome Giocatore (`playerId`).
  * Selezione automatica dell'IP Tailscale con menu a tendina delle schede di rete trovate.
* **Percorsi Gioco**:
  * Selettore cartella Server Minecraft (`serverDirectory`) con pulsante "Sfoglia...".
  * Dropdown per il comando di avvio con preset automatici (*Forge run.bat*, *Fabric jar*, *Personalizzato*).
  * Selettore Istanze Prism Launcher: scansiona automaticamente la cartella `.tools/PrismLauncher/instances/` (e l'installazione di sistema) e permette di selezionare il modpack installato da un semplice menu a tendina.
* **Archiviazione Git / Cloud**:
  * Inserimento URL repository e GitHub Personal Access Token con pulsante **"Testa Connessione"** (verifica permessi di lettura/scrittura prima di salvare).
* **Regole di Gioco & Whitelist**:
  * Editor della whitelist dei giocatori (aggiunta/rimozione con un clic).
  * Slider per RAM Minima e Massima (aggiorna direttamente `user_jvm_args.txt` o gli argomenti Java).

#### Schermata 4: Centro Diagnostico Intelligente (Log & Assistente Risoluzione Problemi)
Quando si verifica un errore durante l'avvio, il download o l'esecuzione del server, la GUI cattura l'eccezione e presenta una scheda di **Diagnostica Guidata**:
* **Box Errore**: Mostra il messaggio chiave estratto dal log (es. *Porta 25565 già in uso*, *File jar corrotto o mancante*, *Getsockopt timeout*, *Token GitHub non valido*).
* **Spiegazione Umana**: "Cosa significa questo errore in parole semplici".
* **Azione Consigliata / Pulsante Fix**:
  * *Esempio*: Se rileva `Connection timed out: getsockopt`, offre il pulsante **"Allinea IP Client a Tailscale"** che corregge automaticamente `config.json`.
  * *Esempio*: Se rileva `Invalid or corrupt jarfile run.bat`, offre il pulsante **"Correggi comando in run.bat"**.
  * *Esempio*: Se il lock è rimasto bloccato da un arresto anomalo precedente, offre il pulsante **"Sblocca Sessione (Force Unlock)"**.

---

# PARTE 3: GUIDA OPERATIVA PER L'IMPLEMENTAZIONE (Istruzioni per Antigravity)

1. **Dipendenze Maven (`pom.xml`)**:
   * Aggiungere `com.formdev:flatlaf:3.5.1` per il rendering moderno della GUI Swing.
2. **Struttura dei Nuovi Package**:
   * `com.mcsync.gui` -> Finestre principali, componenti di monitoraggio, terminale interattivo.
   * `com.mcsync.gui.controller` -> Binding tra gli eventi dell'interfaccia utente e `MainOrchestrator`.
   * `com.mcsync.diagnostics` -> Motore euristico di analisi log per identificare errori noti e suggerire soluzioni.
3. **Modifiche a `com.mcsync.core.ModsSyncManager`**:
   * Introdurre il filtro selettivo che esclude i file di configurazione (`config/`) quando il ruolo in esecuzione è `CLIENT` e la policy è `MODS_ONLY`.
4. **Modifiche a `launch.bat` e `launch.sh`**:
   * Assicurarsi che l'avvio predefinito apra la GUI, supportando l'opzione `--no-gui` per l'esecuzione tradizionale da riga di comando.
5. **Aggiornamento di `README.md`**:
   * Riscrivere la documentazione integrando screenshot mockup della GUI, istruzioni passo-passo per Server Pack ed elenco completo dei codici di risoluzione problemi.

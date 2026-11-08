# Report Bug & Risoluzioni - mc-sync-launcher

Questo documento riassume in dettaglio tutti i problemi tecnici, bug di configurazione e conflitti d'ambiente riscontrati durante il setup di **mc-sync-launcher** per il modpack **FTB StoneBlock 3** (Forge 1.18.2) e le relative soluzioni applicate.

---

## 1. Connettività di Rete Tailscale: Host Online ma Porta Non Raggiungibile

### Sintomo
Durante l'avvio del launcher in modalità client, veniva mostrato ripetutamente il messaggio:
```text
'Domeiannetta' reports ONLINE but 100.118.202.43:25565 is not reachable yet, retrying ... (6s)
```
Il comando `tailscale ping 100.118.202.43` restituiva:
```text
no matching peer
```

### Causa
1. **Heartbeat GitHub attivo:** Il launcher dell'host (`Domeiannetta`) inviava regolarmente i battiti sul branch `mcsync-state` della repository GitHub, aggiornando `lock.json`. Per questo motivo il sistema lo considerava correttamente "ONLINE".
2. **Account Tailscale separati:** I due PC appartenevano a due account Tailscale distinti (`p.sava2005@gmail.com` e `domeiannetta@...`) e non facevano parte della stessa Tailnet. Nessuna delle due macchine era stata condivisa tramite "Node Sharing", rendendo l'IP `100.118.202.43` invisibile e non instradabile dal PC locale.

### Risoluzione
* È stata completata la procedura di invito/condivisione del nodo nella rete Tailscale.
* Verificata la corretta visibilità reciproca (`tailscale status`) e la risposta ICMP (`tailscale ping 100.118.202.43` -> `pong via DERP in ~86ms`).
* Aggiornato l'indirizzo IP locale assegnato sulla nuova Tailnet (`100.70.79.102`).

---

## 2. Crash all'Avvio del Server Forge: Mismatch Versione Java (Java 8 vs Java 17+)

### Sintomo
Quando il launcher tentava di avviare il server Forge in modalità host, il processo terminava immediatamente con exit code 0/1 senza produrre log di Minecraft:
```text
[server] C:\MinecraftServer>java @user_jvm_args.txt @libraries/net/minecraftforge/forge/1.18.2-40.2.34/win_args.txt nogui
[server] Errore: impossibile trovare o caricare la classe principale @user_jvm_args.txt
[server] C:\MinecraftServer>REM pause removed by mc-sync-launcher
[ERROR] MainOrchestrator - The server did not become ready (see the [server] output above / logs/latest.log)
[INFO] ServerProcessController - Server process exited with code 0
```

### Causa
* Nel PATH di sistema di Windows, il comando globale `java` richiamava **Java 8 (1.8.0_481)** installato in `C:\Program Files (x86)\Common Files\Oracle\Java\java8path\java.exe`.
* La sintassi dei file di argomenti (`@argfile`, ovvero `@user_jvm_args.txt` e `@win_args.txt`) è stata introdotta in Java solo a partire da **Java 9**.
* Java 8 non supporta la sintassi `@` e interpretava `@user_jvm_args.txt` come il nome letterale di una classe Java da caricare, fallendo istantaneamente. Inoltre, Forge 1.18.2 richiede obbligatoriamente **Java 17 o superiore**.

### Risoluzione
* Modificato lo script [`C:\MinecraftServer\run.bat`](file:///C:/MinecraftServer/run.bat), sostituendo la chiamata generica a `java` con il percorso assoluto del binario **Temurin OpenJDK 21** a 64-bit scaricato dal launcher:
  ```bat
  "C:\Users\psava\Desktop\mc-sync-launcher\.tools\jdk-21\bin\java.exe" @user_jvm_args.txt @libraries/net/minecraftforge/forge/1.18.2-40.2.34/win_args.txt nogui %*
  ```

---

## 3. Percorso di Esecuzione Errato in `clientLaunchCommand`

### Sintomo
Fallimento o blocco dell'avvio automatico del client Minecraft da parte del launcher.

### Causa
Nel file `config.json` originale, la configurazione per il client puntava alla **cartella** del programma anziché all'eseguibile:
```json
"clientLaunchCommand": [
  "C:\\Users\\psava\\Desktop\\mc-sync-launcher\\.tools\\PrismLauncher",
  ...
]
```
Windows non consente l'esecuzione diretta di una directory, sollevando un'eccezione di I/O nel processo Java.

### Risoluzione
* Configurato il percorso assoluto all'eseguibile [`prismlauncher.exe`](file:///C:/Users/psava/Desktop/mc-sync-launcher/.tools/PrismLauncher/prismlauncher.exe) e allineati i parametri di lancio:
  ```json
  "clientLaunchCommand": [
    "C:/Users/psava/Desktop/mc-sync-launcher/.tools/PrismLauncher/prismlauncher.exe",
    "--launch",
    "ftb-stoneblock-3-1.11.5",
    "--server",
    "{serverAddress}"
  ]
  ```

---

## 4. Cartella Client Inesistente (`clientInstanceDirectory`) e Struttura Prism Launcher

### Sintomo
Nei log compariva il warning:
```text
[WARN] ModsSyncManager - clientInstanceDirectory does not exist: ... (client mods not synced)
```

### Causa
1. La configurazione di default cercava l'istanza in `%APPDATA%/PrismLauncher/...`, ma il launcher scaricato è in modalità **Portable** (`.tools\PrismLauncher\instances`).
2. Su Windows, le istanze di Prism Launcher memorizzano i file di gioco nella sottocartella `minecraft` **senza il punto iniziale** (a differenza del classico `.minecraft` di CurseForge o vanilla).

### Risoluzione
* Identificata la cartella reale dell'istanza in Prism Launcher e aggiornato [config.json](file:///C:/Users/psava/Desktop/mc-sync-launcher/config.json#L43):
  ```json
  "clientInstanceDirectory": "C:/Users/psava/Desktop/mc-sync-launcher/.tools/PrismLauncher/instances/ftb-stoneblock-3-1.11.5/minecraft"
  ```
* In questo modo `ModsSyncManager` è ora in grado di confrontare gli hash SHA256 dei file (`mods/`, `config/`, `defaultconfigs/`, `kubejs/`) ed eseguire la sincronizzazione differenziale direttamente nella cartella di gioco attiva.

---

## 5. Disallineamento Modpack Forge vs Template Fabric Server

### Sintomo
Inizialmente `serverStartCommand` era configurato con `fabric-server-launch.jar`, mentre i file del mondo e del pacchetto contenevano mod Forge 1.18.2 (`Mekanism`, `Ars Nouveau`, `Blood Magic`, `FTB Quests`).

### Causa
`config.json` conservava il comando di default generato dal template iniziale per server Fabric.

### Risoluzione
* Aggiornato il comando di avvio server in [config.json](file:///C:/Users/psava/Desktop/mc-sync-launcher/config.json#L11-L13):
  ```json
  "serverStartCommand": [
    "run.bat"
  ]
  ```
  consentendo a `run.bat` di gestire l'inizializzazione corretta delle librerie Forge e dei parametri JVM.

---

## Riepilogo Stato Attuale
| Componente | Stato Precedente | Stato Attuale | Note |
|---|---|---|---|
| **Rete Tailscale** | `no matching peer` | **Connesso** (`pong ~86ms`) | Entrambi i PC sulla stessa Tailnet |
| **Java Server** | Java 8 (`Errore @argfile`) | **Java 21 (Temurin)** | Configurato percorso in `run.bat` |
| **Prism Launcher CLI** | Directory puntata | **`prismlauncher.exe`** | Percorso assoluto configurato |
| **Sync Mod Client** | Directory inesistente | **`instances/.../minecraft`** | Allineato all'istanza StoneBlock 3 |
| **Tipo Server** | Fabric (incompatibile) | **Forge 1.18.2 (`run.bat`)** | Pronto per avvio host locale |

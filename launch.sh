#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAR_PATH="$SCRIPT_DIR/mcServer4all.jar"
if [[ ! -f "$JAR_PATH" ]]; then
    if [[ -f "$SCRIPT_DIR/mc-sync-launcher.jar" ]]; then
        JAR_PATH="$SCRIPT_DIR/mc-sync-launcher.jar"
    elif [[ -f "$SCRIPT_DIR/target/mcServer4all.jar" ]]; then
        JAR_PATH="$SCRIPT_DIR/target/mcServer4all.jar"
    elif [[ -f "$SCRIPT_DIR/target/mc-sync-launcher.jar" ]]; then
        JAR_PATH="$SCRIPT_DIR/target/mc-sync-launcher.jar"
    else
        echo "[ERROR] mcServer4all.jar non trovato in $SCRIPT_DIR ne' in target/." >&2
        exit 1
    fi
fi

check_java() {
    local bin="$1"
    if [[ ! -x "$bin" ]]; then return 1; fi
    local ver_raw
    ver_raw="$("$bin" -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
    local major="${ver_raw%%.*}"
    major="${major%%-*}"
    if [[ -n "$major" && "$major" =~ ^[0-9]+$ ]] && (( major >= 21 )); then
        JAVA_BIN="$bin"
        return 0
    fi
    return 1
}

JAVA_BIN=""
if check_java "$SCRIPT_DIR/.tools/jdk-21/bin/java"; then
    :
elif [[ -n "${JAVA_HOME:-}" ]] && check_java "$JAVA_HOME/bin/java"; then
    :
elif command -v java >/dev/null 2>&1 && check_java "$(command -v java)"; then
    :
else
    echo "[INFO] Java 21+ non trovato. Avvio download automatico di OpenJDK 21 portatile in .tools/jdk-21..."
    mkdir -p "$SCRIPT_DIR/.tools/jdk-21"
    OS_NAME="$(uname -s | tr '[:upper:]' '[:lower:]')"
    ARCH_NAME="$(uname -m)"
    [[ "$OS_NAME" == "darwin" ]] && OS_NAME="mac"
    [[ "$ARCH_NAME" == "x86_64" || "$ARCH_NAME" == "amd64" ]] && ARCH_NAME="x64"
    [[ "$ARCH_NAME" == "arm64" ]] && ARCH_NAME="aarch64"
    ADOPTIUM_URL="https://api.adoptium.net/v3/binary/latest/21/ga/${OS_NAME}/${ARCH_NAME}/jdk/hotspot/normal/eclipse?project=jdk"
    curl -fSL "$ADOPTIUM_URL" -o "$SCRIPT_DIR/.tools/jdk21.tar.gz"
    tar xzf "$SCRIPT_DIR/.tools/jdk21.tar.gz" -C "$SCRIPT_DIR/.tools/jdk-21" --strip-components=1
    rm -f "$SCRIPT_DIR/.tools/jdk21.tar.gz"
    if [[ -x "$SCRIPT_DIR/.tools/jdk-21/Contents/Home/bin/java" ]]; then
        JAVA_BIN="$SCRIPT_DIR/.tools/jdk-21/Contents/Home/bin/java"
    else
        JAVA_BIN="$SCRIPT_DIR/.tools/jdk-21/bin/java"
    fi
fi

if [[ $# -eq 0 && ! -f "$SCRIPT_DIR/config.json" ]]; then
    "$JAVA_BIN" --enable-native-access=ALL-UNNAMED -jar "$JAR_PATH" init --config "$SCRIPT_DIR/config.json" >/dev/null
fi

exec "$JAVA_BIN" --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -jar "$JAR_PATH" "$@"

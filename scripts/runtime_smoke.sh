#!/usr/bin/env bash
set -euo pipefail

FAMILY="${1:?Usage: runtime_smoke.sh <paper|purpur|leaf|folia> <minecraft-version> <channel> <addon-jar> [work-directory] [expectation]}"
MC_VERSION="${2:?Usage: runtime_smoke.sh <paper|purpur|leaf|folia> <minecraft-version> <channel> <addon-jar> [work-directory] [expectation]}"
CHANNEL="${3:?Usage: runtime_smoke.sh <paper|purpur|leaf|folia> <minecraft-version> <channel> <addon-jar> [work-directory] [expectation]}"
ADDON_JAR="${4:?Usage: runtime_smoke.sh <paper|purpur|leaf|folia> <minecraft-version> <channel> <addon-jar> [work-directory] [expectation]}"
WORK_DIR="${5:-build/runtime-smoke-${FAMILY}-${MC_VERSION}}"
EXPECTATION="${6:-supported}"

SLIMEFUN_VERSION="${SLIMEFUN_VERSION:-4.1.75}"
SLIMEFUN_URL="${SLIMEFUN_URL:-https://github.com/wickidcow/Slimefun-Legacy/releases/download/v${SLIMEFUN_VERSION}/Slimefun-Legacy${SLIMEFUN_VERSION}.jar}"
USER_AGENT="${RUNTIME_SMOKE_USER_AGENT:-SF_SlimeHUD-CI/2.0.5 (https://github.com/wickidcow/SF_SlimeHUD)}"
STARTUP_TIMEOUT_SECONDS="${RUNTIME_SMOKE_STARTUP_TIMEOUT:-300}"
SHUTDOWN_TIMEOUT_SECONDS="${RUNTIME_SMOKE_SHUTDOWN_TIMEOUT:-60}"
WIT_VERSION_ID="${WIT_VERSION_ID:-}"

for command in curl jq java; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Required command is unavailable: $command" >&2
        exit 1
    fi
done

if [[ ! -s "$ADDON_JAR" ]]; then
    echo "Addon JAR not found or empty: $ADDON_JAR" >&2
    exit 1
fi

case "$FAMILY" in
    paper|purpur|leaf|folia) ;;
    *)
        echo "Unsupported server family: $FAMILY" >&2
        exit 1
        ;;
esac

case "$EXPECTATION" in
    supported|slimefun-version-gate) ;;
    *)
        echo "Unsupported runtime expectation: $EXPECTATION" >&2
        exit 1
        ;;
esac

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/plugins"

curl --fail-with-body -L -sS -H "User-Agent: ${USER_AGENT}" \
    -o "$WORK_DIR/plugins/Slimefun-Legacy${SLIMEFUN_VERSION}.jar" \
    "$SLIMEFUN_URL"
test -s "$WORK_DIR/plugins/Slimefun-Legacy${SLIMEFUN_VERSION}.jar"
cp "$ADDON_JAR" "$WORK_DIR/plugins/SF_SlimeHUD2.0.5.jar"
mkdir -p "$WORK_DIR/plugins/Slimefun"
cat > "$WORK_DIR/plugins/Slimefun/config.yml" <<'SLIMEFUN_CONFIG'
options:
  auto-update: false
SLIMEFUN_CONFIG

if [[ -n "$WIT_VERSION_ID" ]]; then
    WIT_METADATA="$(curl --fail-with-body -sS -H "User-Agent: ${USER_AGENT}" "https://api.modrinth.com/v2/version/${WIT_VERSION_ID}")"
    WIT_URL="$(jq -r '([.files[] | select(.primary == true)][0].url // .files[0].url // empty)' <<<"$WIT_METADATA")"
    if [[ -z "$WIT_URL" || "$WIT_URL" == "null" ]]; then
        echo "Could not resolve What Is That? version ${WIT_VERSION_ID}." >&2
        exit 1
    fi
    curl --fail-with-body -L -sS -H "User-Agent: ${USER_AGENT}" -o "$WORK_DIR/plugins/WIT.jar" "$WIT_URL"
    test -s "$WORK_DIR/plugins/WIT.jar"
fi

SERVER_URL=""
SERVER_BUILD=""
SERVER_CHANNEL=""

resolve_papermc_server() {
    local project="$1"
    local response
    response="$(curl --fail-with-body -sS -H "User-Agent: ${USER_AGENT}" \
        "https://fill.papermc.io/v3/projects/${project}/versions/${MC_VERSION}/builds")"

    if jq -e '.ok == false' >/dev/null 2>&1 <<<"$response"; then
        jq -r '.message // "PaperMC downloads service returned an unknown error"' <<<"$response" >&2
        return 1
    fi

    if [[ "$CHANNEL" == "ANY" ]]; then
        SERVER_URL="$(jq -r 'first(.[]) | .downloads."server:default".url // empty' <<<"$response")"
        SERVER_BUILD="$(jq -r 'first(.[]) | .id // empty' <<<"$response")"
        SERVER_CHANNEL="$(jq -r 'first(.[]) | .channel // "UNKNOWN"' <<<"$response")"
    else
        SERVER_URL="$(jq -r --arg channel "$CHANNEL" 'first(.[] | select((.channel | ascii_upcase) == ($channel | ascii_upcase))) | .downloads."server:default".url // empty' <<<"$response")"
        SERVER_BUILD="$(jq -r --arg channel "$CHANNEL" 'first(.[] | select((.channel | ascii_upcase) == ($channel | ascii_upcase))) | .id // empty' <<<"$response")"
        SERVER_CHANNEL="$(jq -r --arg channel "$CHANNEL" 'first(.[] | select((.channel | ascii_upcase) == ($channel | ascii_upcase))) | .channel // "UNKNOWN"' <<<"$response")"
    fi
}

resolve_purpur_server() {
    local response
    response="$(curl --fail-with-body -sS -H "User-Agent: ${USER_AGENT}" \
        "https://api.purpurmc.org/v2/purpur/${MC_VERSION}")"
    SERVER_BUILD="$(jq -r '.builds.latest // (.builds.all[-1] // empty)' <<<"$response")"
    SERVER_CHANNEL="PURPUR"
    if [[ -n "$SERVER_BUILD" && "$SERVER_BUILD" != "null" ]]; then
        SERVER_URL="https://api.purpurmc.org/v2/purpur/${MC_VERSION}/${SERVER_BUILD}/download"
    fi
}

resolve_leaf_server() {
    local response
    response="$(curl --fail-with-body -sS -H "User-Agent: ${USER_AGENT}" \
        "https://api.leafmc.one/v2/projects/leaf/versions/${MC_VERSION}")"
    SERVER_BUILD="$(jq -r '.builds[-1] // empty' <<<"$response")"
    SERVER_CHANNEL="LEAF"
    if [[ -n "$SERVER_BUILD" && "$SERVER_BUILD" != "null" ]]; then
        SERVER_URL="https://api.leafmc.one/v2/projects/leaf/versions/${MC_VERSION}/builds/${SERVER_BUILD}/downloads/leaf-${MC_VERSION}-${SERVER_BUILD}.jar"
    fi
}

case "$FAMILY" in
    paper|folia) resolve_papermc_server "$FAMILY" ;;
    purpur) resolve_purpur_server ;;
    leaf) resolve_leaf_server ;;
esac

if [[ -z "$SERVER_URL" || "$SERVER_URL" == "null" || -z "$SERVER_BUILD" || "$SERVER_BUILD" == "null" ]]; then
    echo "Could not resolve a ${FAMILY} ${MC_VERSION} build (requested channel: ${CHANNEL})." >&2
    exit 1
fi

curl --fail-with-body -L -sS -H "User-Agent: ${USER_AGENT}" -o "$WORK_DIR/server.jar" "$SERVER_URL"
test -s "$WORK_DIR/server.jar"

printf 'eula=true\n' > "$WORK_DIR/eula.txt"
cat > "$WORK_DIR/server.properties" <<'PROPERTIES'
online-mode=false
server-ip=127.0.0.1
server-port=0
level-name=slimehud-smoke-world
max-players=1
spawn-protection=0
view-distance=2
simulation-distance=2
pause-when-empty-seconds=-1
enable-query=false
enable-rcon=false
motd=SF_SlimeHUD runtime smoke
PROPERTIES

cat > "$WORK_DIR/server-build.txt" <<EOF_BUILD
Server family: ${FAMILY}
Minecraft: ${MC_VERSION}
Build: ${SERVER_BUILD}
Channel: ${SERVER_CHANNEL}
Download: ${SERVER_URL}
Java: $(java -version 2>&1 | head -n 1)
Slimefun Legacy: ${SLIMEFUN_VERSION}
Addon: SF_SlimeHUD 2.0.5
What Is That?: ${WIT_VERSION_ID:-not installed}
Expectation: ${EXPECTATION}
EOF_BUILD

INPUT_FIFO="$WORK_DIR/server.stdin"
CONSOLE_LOG="$WORK_DIR/server.console.log"
mkfifo "$INPUT_FIFO"
exec 3<>"$INPUT_FIFO"

(
    cd "$WORK_DIR"
    java -Xms512M -Xmx2G -jar server.jar --nogui <&3 > server.console.log 2>&1
) &
SERVER_PID=$!

stop_server() {
    if kill -0 "$SERVER_PID" >/dev/null 2>&1; then
        printf 'stop\n' >&3 || true
    fi
    local deadline=$((SECONDS + SHUTDOWN_TIMEOUT_SECONDS))
    while kill -0 "$SERVER_PID" >/dev/null 2>&1 && (( SECONDS < deadline )); do
        sleep 1
    done
    if kill -0 "$SERVER_PID" >/dev/null 2>&1; then
        echo "Server did not stop within ${SHUTDOWN_TIMEOUT_SECONDS}s; terminating it." >&2
        kill "$SERVER_PID" >/dev/null 2>&1 || true
        sleep 2
    fi
}

STARTED=false
STARTUP_DEADLINE=$((SECONDS + STARTUP_TIMEOUT_SECONDS))
while kill -0 "$SERVER_PID" >/dev/null 2>&1 && (( SECONDS < STARTUP_DEADLINE )); do
    if grep -Fq 'Done (' "$CONSOLE_LOG" 2>/dev/null; then
        STARTED=true
        break
    fi
    if grep -Fq 'Error occurred while enabling SlimeHUD' "$CONSOLE_LOG" 2>/dev/null || \
       grep -Fq 'Error occurred while enabling Slimefun' "$CONSOLE_LOG" 2>/dev/null; then
        break
    fi
    sleep 2
done

if [[ "$STARTED" != true ]]; then
    echo "${FAMILY} ${MC_VERSION}: server did not reach Done." >&2
    stop_server
    wait "$SERVER_PID" >/dev/null 2>&1 || true
    exec 3>&-
    cat "$CONSOLE_LOG" >&2 || true
    exit 1
fi

# Give post-start plugin tasks time to expose linkage or scheduler errors.
sleep 8
printf 'plugins\n' >&3 || true
sleep 2
stop_server

SERVER_STATUS=0
wait "$SERVER_PID" || SERVER_STATUS=$?
exec 3>&-

if (( SERVER_STATUS != 0 )); then
    echo "${FAMILY} ${MC_VERSION}: server exited with status ${SERVER_STATUS}." >&2
    cat "$CONSOLE_LOG" >&2 || true
    exit 1
fi

SLIMEFUN_VERSION_GATE=false
if grep -Fq 'You are using an unsupported Minecraft version!' "$CONSOLE_LOG" && \
   grep -Fq "You are running Minecraft ${MC_VERSION}" "$CONSOLE_LOG"; then
    SLIMEFUN_VERSION_GATE=true
fi

if [[ "$EXPECTATION" == "slimefun-version-gate" ]]; then
    if [[ "$SLIMEFUN_VERSION_GATE" != true ]]; then
        echo "${FAMILY} ${MC_VERSION}: expected the Slimefun Legacy version gate, but it was not observed." >&2
        cat "$CONSOLE_LOG" >&2 || true
        exit 1
    fi
    if grep -Fq 'Error occurred while enabling SlimeHUD' "$CONSOLE_LOG" || \
       grep -Fq 'NoClassDefFoundError: io/github/thebusybiscuit/slimefun4' "$CONSOLE_LOG"; then
        echo "${FAMILY} ${MC_VERSION}: Slimefun version gate was expected, but SlimeHUD did not fail gracefully." >&2
        cat "$CONSOLE_LOG" >&2 || true
        exit 1
    fi
    if ! grep -Fq 'Slimefun is unavailable or disabled. SF_SlimeHUD cannot start without its required dependency.' "$CONSOLE_LOG"; then
        echo "${FAMILY} ${MC_VERSION}: expected SlimeHUD dependency-guard message was not observed." >&2
        cat "$CONSOLE_LOG" >&2 || true
        exit 1
    fi

    cat > "$WORK_DIR/smoke-result.txt" <<EOF_RESULT
SF_SlimeHUD runtime smoke: BLOCKED_BY_SLIMEFUN_VERSION_GATE
Server family: ${FAMILY}
Minecraft: ${MC_VERSION}
Build: ${SERVER_BUILD}
Channel: ${SERVER_CHANNEL}
Slimefun Legacy: ${SLIMEFUN_VERSION}
SF_SlimeHUD: 2.0.5
Server reached Done: yes
Slimefun enabled: no
SlimeHUD dependency handling: graceful disable observed
Reason: Slimefun Legacy ${SLIMEFUN_VERSION} rejects Minecraft ${MC_VERSION} before SF_SlimeHUD can be runtime-validated.
SF_SlimeHUD API compile: covered separately by the Paper ${MC_VERSION} compile target.
Addon runtime verdict: not tested because the required dependency disabled itself first.
EOF_RESULT
    cat "$WORK_DIR/smoke-result.txt"
    # Keep the advisory target visibly non-green while classifying the known dependency blocker.
    exit 2
fi

if [[ "$SLIMEFUN_VERSION_GATE" == true ]]; then
    echo "${FAMILY} ${MC_VERSION}: Slimefun Legacy ${SLIMEFUN_VERSION} rejected this Minecraft version unexpectedly." >&2
    cat "$CONSOLE_LOG" >&2 || true
    exit 1
fi

if ! grep -Fq "Enabling Slimefun v${SLIMEFUN_VERSION}" "$CONSOLE_LOG"; then
    echo "${FAMILY} ${MC_VERSION}: Slimefun Legacy ${SLIMEFUN_VERSION} did not enable." >&2
    cat "$CONSOLE_LOG" >&2 || true
    exit 1
fi
if ! grep -Fq 'Enabling SlimeHUD v2.0.5' "$CONSOLE_LOG"; then
    echo "${FAMILY} ${MC_VERSION}: SlimeHUD 2.0.5 enable line was not observed." >&2
    cat "$CONSOLE_LOG" >&2 || true
    exit 1
fi
if [[ -n "$WIT_VERSION_ID" ]]; then
    if ! grep -Fq 'Enabling WIT v' "$CONSOLE_LOG"; then
        echo "${FAMILY} ${MC_VERSION}: WIT enable line was not observed." >&2
        cat "$CONSOLE_LOG" >&2 || true
        exit 1
    fi
    if ! grep -Fq 'What Is That? bridge enabled. WIT will display Slimefun machine data supplied by SF_SlimeHUD.' "$CONSOLE_LOG"; then
        echo "${FAMILY} ${MC_VERSION}: SF_SlimeHUD did not enable its WIT bridge." >&2
        cat "$CONSOLE_LOG" >&2 || true
        exit 1
    fi
fi
if grep -Fq 'Error occurred while enabling SlimeHUD' "$CONSOLE_LOG" || \
   grep -Fq 'Error occurred while enabling Slimefun' "$CONSOLE_LOG" || \
   grep -Fq 'Error occurred while enabling WIT' "$CONSOLE_LOG"; then
    echo "${FAMILY} ${MC_VERSION}: plugin enable failure detected." >&2
    cat "$CONSOLE_LOG" >&2 || true
    exit 1
fi
if grep -Eq 'ERROR.*\[SlimeHUD\]' "$CONSOLE_LOG"; then
    echo "${FAMILY} ${MC_VERSION}: SlimeHUD logged a runtime error." >&2
    grep -E 'ERROR.*\[SlimeHUD\]' "$CONSOLE_LOG" >&2 || true
    exit 1
fi
if ! grep -Fq 'Stopping server' "$CONSOLE_LOG"; then
    echo "${FAMILY} ${MC_VERSION}: clean shutdown was not observed." >&2
    cat "$CONSOLE_LOG" >&2 || true
    exit 1
fi

cat > "$WORK_DIR/smoke-result.txt" <<EOF_RESULT
SF_SlimeHUD runtime smoke: PASS
Server family: ${FAMILY}
Minecraft: ${MC_VERSION}
Build: ${SERVER_BUILD}
Channel: ${SERVER_CHANNEL}
Slimefun Legacy: ${SLIMEFUN_VERSION}
SF_SlimeHUD: 2.0.5
Server reached Done: yes
Slimefun enabled: yes
SlimeHUD enabled: yes
SlimeHUD runtime errors: none observed
What Is That?: ${WIT_VERSION_ID:-not installed}
Clean shutdown: yes
EOF_RESULT
cat "$WORK_DIR/smoke-result.txt"

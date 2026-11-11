#!/usr/bin/env bash
# Sends one hold request several times with one Idempotency-Key, as a client does after timeouts,
# and shows that every reply names the same hold (Module 7, Section 8).
# Run it while ./gradlew -q serve --args='config/serve.properties' is serving.
#
# Usage: scripts/retry-hold.sh <port> <seat> <times> <key> [--run] [--yes]
#   default   a dry run: print the plan and the curl command, send nothing
#   --run     send the requests; asks for confirmation on the terminal first
#   --yes     skip the question (for a reader who has already read the plan)
#
# Guardrails: the server is always 127.0.0.1 and the port must be 1024 to 65535, so no request ever
# leaves this machine; the seat must look like A-7 and the key like the contract's; at most 20
# requests; an audit line is appended to build/retry-hold/audit.log; any failure exits non-zero.
# The requests change data: the first holds the seat for 300 seconds, the rest change nothing.
set -euo pipefail

readonly MAX_TIMES=20
readonly LOWEST_PORT=1024
readonly HIGHEST_PORT=65535
readonly SEAT_PATTERN='^[A-T]-[1-9][0-9]{0,3}$'
readonly KEY_PATTERN='^[A-Za-z0-9_-]{1,64}$'
readonly CURL_TIMEOUT_SECONDS=5
readonly AUDIT_DIR="build/retry-hold"

# Writes words as one line the shell reads back as the same words: a word with a space, a quote or
# a brace is put in single quotes, so the printed command can be pasted as it is.
shell_quoted() {
  local word line=""
  for word in "$@"; do
    if [[ "$word" =~ ^[A-Za-z0-9_./:=-]+$ ]]; then
      line+="$word "
    else
      line+="'${word//\'/\'\\\'\'}' "
    fi
  done
  echo "${line% }"
}

usage() {
  echo "usage: $0 <port> <seat> <times> <key> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 4 ]] || usage
port="$1"
seat="$2"
times="$3"
key="$4"
shift 4
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done

# Check before: the tool and every input.
command -v curl >/dev/null || { echo "curl is not installed" >&2; exit 1; }
[[ "$port" =~ ^[0-9]+$ ]] && (( port >= LOWEST_PORT && port <= HIGHEST_PORT )) \
  || { echo "the port must be $LOWEST_PORT to $HIGHEST_PORT" >&2; exit 2; }
[[ "$seat" =~ $SEAT_PATTERN ]] || { echo "the seat must look like A-7: $seat" >&2; exit 2; }
[[ "$times" =~ ^[0-9]+$ ]] && (( times >= 1 && times <= MAX_TIMES )) \
  || { echo "times must be 1 to $MAX_TIMES" >&2; exit 2; }
[[ "$key" =~ $KEY_PATTERN ]] || { echo "the key must be 1 to 64 of A-Z a-z 0-9 _ -" >&2; exit 2; }

body="{\"eventId\":1,\"seat\":\"$seat\",\"customer\":\"script\"}"
curl_command=(curl --silent --show-error --max-time "$CURL_TIMEOUT_SECONDS" \
  --output /dev/stderr --write-out '%{http_code}' \
  -H 'Content-Type: application/json' -H "Idempotency-Key: \"$key\"" \
  --data "$body" "http://127.0.0.1:$port/v1/holds")
echo "plan: $times x POST http://127.0.0.1:$port/v1/holds, seat $seat, Idempotency-Key \"$key\""
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing sent. Each request would be:"
  echo "  $(shell_quoted "${curl_command[@]}")"
  echo "add --run to send them"
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  # Without a terminal there is nobody to answer; with set -e a failed read would end the script
  # silently, so the refusal is said out loud instead.
  [[ -t 0 ]] || { echo "nothing sent: no terminal to confirm on; read the plan, then add --yes" >&2; exit 1; }
  answer=""
  read -r -p "send $times requests to 127.0.0.1:$port? [y/N] " answer || true
  [[ "$answer" == "y" ]] || { echo "nothing sent" >&2; exit 1; }
fi

mkdir -p "$AUDIT_DIR"
for attempt in $(seq 1 "$times"); do
  # The body goes to stderr and the status to stdout, so each line shows both.
  status=$("${curl_command[@]}" 2> "$AUDIT_DIR/reply.json")
  echo "attempt $attempt: $status $(cat "$AUDIT_DIR/reply.json")"
done

# Check after: the store must hold the key once, whatever the number of attempts.
curl --silent --show-error --max-time "$CURL_TIMEOUT_SECONDS" "http://127.0.0.1:$port/metrics" \
  | grep -E '^seatwave_idempotency_(outcomes_total\{endpoint="POST /v1/holds"|keys_stored)'
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) sent $times holds of $seat with key $key to 127.0.0.1:$port" \
  >> "$AUDIT_DIR/audit.log"
echo "audit line added to $AUDIT_DIR/audit.log"

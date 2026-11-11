# Bash and `scripts/retry-hold.sh`

## What it is

A **terminal** is a window where you type commands. A **shell** is the program inside it that reads what you type and starts other programs for you. **Bash** is one very common shell, and also a small programming language: you can save commands in a file, called a **script**, and run the file. The script in this repository, `scripts/retry-hold.sh`, is written in Bash. The `.sh` ending is only a habit; the first line decides which program runs the file.

A **command** is a program name followed by **arguments**, the words that tell it what to do: `curl --max-time 5 http://127.0.0.1:8080/health`. A **variable** is a named box for text: `port="8080"` stores the text, `$port` reads it. Every command ends with an **exit code**, a number from 0 to 255. By agreement 0 means "it worked" and any other number means "it failed". The shell remembers the last one in `$?`.

**curl** is a command-line program that sends one HTTP request and prints the reply. HTTP is the language a client and a server speak over the network: the client sends a method (`POST`), a path (`/v1/holds`), some headers and perhaps a body; the server answers with a status number and a body.

## Why this repository uses it

The lesson of this repository is the **retry**. A phone loses its signal after it has sent "hold seat A-7", and the app sends the same request again. The API must not hold two seats or charge twice. The Java tests prove that with 100 threads; this script lets a learner see it with their own eyes: it sends one hold request several times with the same `Idempotency-Key` and shows that every reply names the same hold. A shell script is the right size for that: it only starts `curl` a few times and prints lines.

The script is also a lesson in safe tooling. Its default is a **dry run** that sends nothing, a real run needs `--run`, it asks for a confirmation, it only talks to `127.0.0.1` on a high port, it sends at most 20 requests, and it writes an audit line.

## Where it appears

| Path | What it does |
| --- | --- |
| `scripts/retry-hold.sh` | The only script. 84 lines, mode `-rwxr-xr-x` (executable). |
| `README.md` | Shows a session that starts the server and runs the script. |
| `config/serve.properties` | Sets the port of the server that the script talks to. |
| `.gitignore` | Keeps `build/retry-hold/` (the audit log and the last reply) out of Git (`git.md`). |

## How it works underneath

1. You type `scripts/retry-hold.sh 8080 A-7 3 k1`. The shell sees that the file is executable and reads its first line, `#!/usr/bin/env bash`. A line that starts with `#!` (the **shebang**) names the program that must run the file. `env bash` means "find `bash` on the PATH", the list of folders where programs live.
2. Bash starts as a new **process** (a running program) and reads the script one command at a time. The words you typed after the script name arrive as `$1`, `$2`, `$3`, `$4` and the following numbers. `$#` is how many there are, `$0` is the script's own name and `"$@"` is all of them.
3. The script checks every input first. Any check that fails prints a message to **standard error**, the stream for messages about problems (`>&2` sends a message there), and stops with a code.
4. In dry-run mode it prints the plan and the `curl` command and stops with code 0. Nothing was sent.
5. In run mode, it asks you to type `y`, then loops: start `curl` with the same key, print the status and the body.
6. After the loop it reads the server's `/metrics` page and keeps the two lines that prove the key was stored once, appends an audit line and exits 0.

The requests travel over the loopback address `127.0.0.1`, which never leaves the computer. A **port** is a number that tells the operating system which program on this computer should receive a packet. Ports below 1024 are **privileged** on Unix: only the administrator may listen on them. The server uses a port above 1024 so no administrator rights are needed.

## Walkthrough of this repository's use

### The header (lines 1 to 14)

```bash
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
```

Line 1 is the shebang explained above. Every line that starts with `#` is a **comment**: Bash ignores it, and people read it. The comments are the script's manual:
- The usage line shows the four required arguments (`<port> <seat> <times> <key>`) and the two optional words in square brackets, `[--run]` and `[--yes]`.
- Without options the script is a dry run: it prints the plan and the `curl` command and sends nothing.
- `--run` sends the requests and asks for confirmation on the terminal first. `--yes` skips the question.
- The guardrails: the server is always `127.0.0.1`, the port must be 1024 to 65535, the seat must look like `A-7`, the key must look like the contract's, at most 20 requests, an audit line is appended to `build/retry-hold/audit.log`, and any failure exits with a non-zero code.
- The last comment warns that the requests change data: the first holds the seat for 300 seconds, the rest change nothing (the server replays the first reply).

### Strict mode (line 15)

```bash
set -euo pipefail
```

One line, three switches:
- `set -e` makes the script stop at the first command that fails (exits non-zero), instead of carrying on with wrong data. A failing command that is part of a test (after `if`, `||`, `&&`) does not count, because there the failure is the point.
- `set -u` makes it an error to read a variable that was never set. A typing mistake such as `$prot` then stops the script instead of becoming empty text.
- `set -o pipefail` changes how a **pipe** is judged. A pipe `a | b | c` sends the output of `a` into `b` and on into `c`. Normally the pipe's exit code is that of the last command only. With `pipefail` it fails if any command in it failed.

### Constants (lines 17 to 23)

```bash
readonly MAX_TIMES=20
readonly LOWEST_PORT=1024
readonly HIGHEST_PORT=65535
readonly SEAT_PATTERN='^[A-T]-[1-9][0-9]{0,3}$'
readonly KEY_PATTERN='^[A-Za-z0-9_-]{1,64}$'
readonly CURL_TIMEOUT_SECONDS=5
readonly AUDIT_DIR="build/retry-hold"
```

- `readonly NAME=value` makes a variable that cannot be changed later. The upper-case names are a habit for fixed values.
- `MAX_TIMES=20` is the most requests one run may send. `LOWEST_PORT=1024` and `HIGHEST_PORT=65535` bound the port: 65535 is the largest number a port can have.
- `SEAT_PATTERN='^[A-T]-[1-9][0-9]{0,3}$'` is a **regular expression**, a pattern for text. `^` means "the text starts here" and `$` means "the text ends here". `[A-T]` is one capital letter from A to T (the seed uses sections A to T). `-` is a literal dash. `[1-9]` is one digit from 1 to 9, so the seat number cannot start with 0. `[0-9]{0,3}` is zero to three more digits. So `A-7`, `T-1000` and `B-12` fit; `A-0`, `Z-7` and `a-7` do not. The single quotes stop the shell from touching the `$` and the braces.
- `KEY_PATTERN='^[A-Za-z0-9_-]{1,64}$'` is the same kind of pattern for the `Idempotency-Key`: one to 64 characters, each a letter, a digit, an underscore or a dash. It matches the rule in the OpenAPI contract.
- `CURL_TIMEOUT_SECONDS=5` is how long `curl` may wait for one reply.
- `AUDIT_DIR="build/retry-hold"` is the folder for the audit log and the last reply. Double quotes let a variable inside be expanded; here there is none.

### The `usage` function (lines 25 to 28)

```bash
usage() {
  echo "usage: $0 <port> <seat> <times> <key> [--run] [--yes]" >&2
  exit 2
}
```

- A **function** is a named group of commands. `usage() { ... }` defines one; it runs when the script writes `usage`.
- `echo "usage: $0 <port> <seat> <times> <key> [--run] [--yes]" >&2` prints the usage text. `$0` is the name the script was started with, so the text shows `scripts/retry-hold.sh`. `>&2` sends it to standard error.
- `exit 2` stops the script with code 2. By convention 2 means "the command was used wrongly".

### Reading the arguments (lines 30 to 44)

```bash
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
```

- `[[ $# -ge 4 ]] || usage`. `[[ ... ]]` is Bash's test. `$#` is the number of arguments and `-ge` means "greater than or equal". The `||` means "or else run": if the test fails, `usage` runs and the script ends. A line like `test || action` is the short way to say "if not, do this".
- `port="$1"`, `seat="$2"`, `times="$3"` and `key="$4"` copy the first four arguments into variables with readable names. The double quotes keep a value with spaces together as one word.
- `shift 4` throws the first four arguments away, so `$1` is now the first extra word (an option).
- `mode="dry-run"` and `confirmed="no"` are the defaults. Nothing is sent unless a later line changes `mode`.
- `for option in "$@"; do ... done` loops over the remaining words. `"$@"` expands to each remaining argument as its own word.
- `case "$option" in ... esac` compares the word with patterns. `--run) mode="run" ;;` sets run mode. `--yes) confirmed="yes" ;;` records that the question is skipped. `*) usage ;;` is the catch-all: any other word is an error. `;;` ends one branch.

### Checking the tool and every input (lines 46 to 53)

```bash
# Check before: the tool and every input.
command -v curl >/dev/null || { echo "curl is not installed" >&2; exit 1; }
[[ "$port" =~ ^[0-9]+$ ]] && (( port >= LOWEST_PORT && port <= HIGHEST_PORT )) \
  || { echo "the port must be $LOWEST_PORT to $HIGHEST_PORT" >&2; exit 2; }
[[ "$seat" =~ $SEAT_PATTERN ]] || { echo "the seat must look like A-7: $seat" >&2; exit 2; }
[[ "$times" =~ ^[0-9]+$ ]] && (( times >= 1 && times <= MAX_TIMES )) \
  || { echo "times must be 1 to $MAX_TIMES" >&2; exit 2; }
[[ "$key" =~ $KEY_PATTERN ]] || { echo "the key must be 1 to 64 of A-Z a-z 0-9 _ -" >&2; exit 2; }
```

- `command -v curl >/dev/null` asks the shell whether a command named `curl` exists. `>/dev/null` throws the printed path away. If the command is missing, `command -v` fails and the group after `||` runs: `{ echo "curl is not installed" >&2; exit 1; }` prints a message and stops with code 1. A `{ ...; }` group runs several commands as one.
- Lines 48 and 49 check the port. `[[ "$port" =~ ^[0-9]+$ ]]` tests that the text is only digits (`=~` means "matches this regular expression"; `+` means one or more). Only then does `(( port >= LOWEST_PORT && port <= HIGHEST_PORT ))` run: `(( ... ))` is Bash arithmetic, and `&&` means "and". The order matters: a word like `abc` is stopped by the first test, so the arithmetic never sees text. The backslash at the end of line 48 joins it to line 49, where `|| { ...; exit 2; }` prints "the port must be 1024 to 65535" and stops with code 2 if either test failed.
- Line 50 checks the seat against `SEAT_PATTERN`. The variable is on the right side without quotes: that is how Bash knows it is a pattern and not a plain string.
- Lines 51 and 52 check `times` the same way as the port: digits only, then `times >= 1 && times <= MAX_TIMES`.
- Line 53 checks the key against `KEY_PATTERN`.

So by line 54 the script knows the tool exists and the four values are safe. They cannot contain a quote, a space, a slash or a shell symbol, which matters because they are used inside a JSON body and a URL.

### Building the request (lines 55 to 59)

```bash
body="{\"eventId\":1,\"seat\":\"$seat\",\"customer\":\"script\"}"
curl_command=(curl --silent --show-error --max-time "$CURL_TIMEOUT_SECONDS" \
  --output /dev/stderr --write-out '%{http_code}' \
  -H 'Content-Type: application/json' -H "Idempotency-Key: \"$key\"" \
  --data "$body" "http://127.0.0.1:$port/v1/holds")
```

- Line 55 builds the JSON body: `{"eventId":1,"seat":"A-7","customer":"script"}`. The backslashes (`\"`) put a literal double quote inside a double-quoted string. `$seat` is replaced with the checked seat.
- `curl_command=( ... )` stores a **list** (an array) of words, one per element. Keeping the command as a list instead of one long string means a word with quotes or spaces stays one word later.
- `curl --silent --show-error --max-time "$CURL_TIMEOUT_SECONDS"`: `--silent` hides curl's progress meter. `--show-error` still prints a real error, such as "connection refused". `--max-time 5` stops waiting after 5 seconds.
- `--output /dev/stderr` sends the reply body to standard error instead of standard output, and `--write-out '%{http_code}'` makes curl print the status number to standard output at the end. That separates the two: the status can be captured as a value, and the body is caught separately in the loop below.
- `-H 'Content-Type: application/json'` is a request header that says the body is JSON. `-H "Idempotency-Key: \"$key\""` is the key. The contract requires it to be a **quoted string**, so the script adds the quotes itself: the header value on the wire is `"k1"` with the quotes.
- `--data "$body"` makes the request a `POST` with that body.
- `"http://127.0.0.1:$port/v1/holds"` is the address. It is fixed to the loopback address; only the port comes from the user.

### The plan and the dry run (lines 60 to 66)

```bash
echo "plan: $times x POST http://127.0.0.1:$port/v1/holds, seat $seat, Idempotency-Key \"$key\""
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing sent. Each request would be:"
  echo "  ${curl_command[*]}"
  echo "add --run to send them"
  exit 0
fi
```

- Line 60 prints the plan in one line, for example `plan: 3 x POST http://127.0.0.1:8080/v1/holds, seat A-7, Idempotency-Key "k1"`.
- `if [[ "$mode" == "dry-run" ]]; then ... fi` runs its body only in the default mode.
- The body prints `dry run: nothing sent. Each request would be:`, then the command. `${curl_command[*]}` joins all the words of the list with spaces. The line only shows the command; it is not run.
- `add --run to send them` tells the learner the next step.
- `exit 0` stops the script. The dry run succeeded, and nothing left the machine.

### The confirmation (lines 67 to 70)

```bash
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "send $times requests to 127.0.0.1:$port? [y/N] " answer
  [[ "$answer" == "y" ]] || { echo "nothing sent" >&2; exit 1; }
fi
```

- `if [[ "$confirmed" != "yes" ]]; then ... fi` asks the question unless `--yes` was given.
- `read -r -p "send $times requests to 127.0.0.1:$port? [y/N] " answer` reads one line the person types into the variable `answer`. `-p` gives the prompt text; Bash shows it only when the input comes from a terminal. `-r` stops the shell from treating backslashes as special characters. The `[y/N]` in the prompt is a convention: the capital letter is the default, here No.
- `[[ "$answer" == "y" ]] || { echo "nothing sent" >&2; exit 1; }` accepts only the exact text `y`. Anything else, including an empty line, prints `nothing sent` and stops with code 1.

### Sending the requests (lines 72 to 77)

```bash
mkdir -p "$AUDIT_DIR"
for attempt in $(seq 1 "$times"); do
  # The body goes to stderr and the status to stdout, so each line shows both.
  status=$("${curl_command[@]}" 2> "$AUDIT_DIR/reply.json")
  echo "attempt $attempt: $status $(cat "$AUDIT_DIR/reply.json")"
done
```

- `mkdir -p "$AUDIT_DIR"` creates the folder `build/retry-hold` if it is missing. `-p` means it is not an error if it already exists.
- `for attempt in $(seq 1 "$times"); do ... done`. `$(command)` runs a command and puts its output in the place of the `$(...)`. `seq 1 3` prints the numbers 1, 2 and 3, one per line. The loop runs once for each.
- The comment on line 74 says why the curl command sends the body to standard error: so that "the body goes to stderr and the status to stdout, so each line shows both".
- `status=$("${curl_command[@]}" 2> "$AUDIT_DIR/reply.json")` runs the saved command. `"${curl_command[@]}"` expands the list to its words, each kept whole. `2> file` sends standard error, which holds the body, into the file `build/retry-hold/reply.json`. What curl writes to standard output, the status number, becomes the value of `status`. Because of `set -e`, a failing curl (for example a server that is not running) ends the script here.
- `echo "attempt $attempt: $status $(cat "$AUDIT_DIR/reply.json")"` prints one line per request: the attempt number, the status and the body that was saved. With the same key, all three lines show `201` and the same `holdId`.

### The check after (lines 79 to 81)

```bash
# Check after: the store must hold the key once, whatever the number of attempts.
curl --silent --show-error --max-time "$CURL_TIMEOUT_SECONDS" "http://127.0.0.1:$port/metrics" \
  | grep -E '^seatwave_idempotency_(outcomes_total\{endpoint="POST /v1/holds"|keys_stored)'
```

- The comment says the aim: the store must hold the key once, whatever the number of attempts.
- `curl ... "http://127.0.0.1:$port/metrics"` reads the server's metrics page, a plain-text list of counters. `|` is a **pipe**: it sends the output of the command on the left to the command on the right.
- `grep -E '^seatwave_idempotency_(outcomes_total\{endpoint="POST /v1/holds"|keys_stored)'` keeps only the lines that start with one of two counter names. `-E` switches on extended regular expressions, where `( | )` means "either of these". `\{` is a literal brace. The two kept lines show how many replies were new and how many were replays for this endpoint, and how many keys the store holds. After three attempts you should see one `outcome="first"` and two `outcome="replayed"`, and `seatwave_idempotency_keys_stored 1`. (This is what the contract tests count, too; I did not run the live send, only the dry run.)

### The audit line (lines 82 to 84)

```bash
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) sent $times holds of $seat with key $key to 127.0.0.1:$port" \
  >> "$AUDIT_DIR/audit.log"
echo "audit line added to $AUDIT_DIR/audit.log"
```

- `date -u +%Y-%m-%dT%H:%M:%SZ` prints the current time in UTC (`-u`) in the ISO 8601 format, for example `2026-11-11T09:00:00Z`.
- `echo "..." >> "$AUDIT_DIR/audit.log"` appends one line. `>>` adds to the end of a file; a single `>` would replace the file. The backslash at the end of line 82 joins it to line 83, where the redirection is.
- The last `echo` tells the learner where the audit line went. The script then ends with the exit code of its last command, 0.

## Try it yourself

Every command below ran in a scratch copy of this repository on 11 November 2026 with GNU bash 5.3.9, and also with the macOS system bash 3.2.57 for the dry run. None of them sends a request: the real send was never run for these notes. The commands are run from the repository folder.

1. The dry run, which is the default:

```
scripts/retry-hold.sh 8080 A-7 3 k1
echo "exit=$?"
```

   Output: `plan: 3 x POST http://127.0.0.1:8080/v1/holds, seat A-7, Idempotency-Key "k1"`, then `dry run: nothing sent. Each request would be:`, the command on one line starting with `  curl --silent --show-error --max-time 5 --output /dev/stderr --write-out %{http_code} -H Content-Type: application/json ...`, then `add --run to send them` and `exit=0`. The printed command has lost its quote marks (the shell took them out before `echo` printed the words), so it is a picture of the request and not a line to paste.

2. Refuse bad input, one case at a time:

```
scripts/retry-hold.sh 80 A-7 3 k1; echo "exit=$?"
scripts/retry-hold.sh 8080 Z-7 3 k1; echo "exit=$?"
scripts/retry-hold.sh 8080 A-7 21 k1; echo "exit=$?"
scripts/retry-hold.sh 8080 A-7 3 bad.key; echo "exit=$?"
```

   Output, in order: `the port must be 1024 to 65535`, `the seat must look like A-7: Z-7`, `times must be 1 to 20` and `the key must be 1 to 64 of A-Z a-z 0-9 _ -`, each followed by `exit=2`. Port `99999` and port `abc` print the same port message. Seat `A-0` and times `0` are refused too.

3. Refuse a wrong call:

```
scripts/retry-hold.sh; echo "exit=$?"
scripts/retry-hold.sh 8080 A-7 3 k1 --bad; echo "exit=$?"
```

   Output: `usage: scripts/retry-hold.sh <port> <seat> <times> <key> [--run] [--yes]` and `exit=2` both times.

4. Say no to the question (the answer comes from a pipe, so nothing is sent):

```
echo n | scripts/retry-hold.sh 8080 A-7 3 k1 --run; echo "exit=$?"
```

   Output: the `plan:` line, `nothing sent` and `exit=1`. The audit folder is not created: `ls build/retry-hold` says `No such file or directory`.

5. See what happens when there is no terminal to ask:

```
scripts/retry-hold.sh 8080 A-7 3 k1 --run < /dev/null; echo "exit=$?"
```

   Output: the `plan:` line and `exit=1`, without the words `nothing sent`. The `read` command fails at the end of input, and `set -e` stops the script on the spot. Nothing is sent.

6. Run it without `curl` on the path:

```
env -i PATH=/nonexistent "$(command -v bash)" scripts/retry-hold.sh 8080 A-7 3 k1; echo "exit=$?"
```

   Output: `curl is not installed` and `exit=1`.

## Mistakes people make

- **Forgetting quotes around a variable.** `$key` with a space inside would split into two words. This script quotes every use.
- **Using `[ ]` or `==` inside `(( ))` carelessly.** The script uses `[[ ]]` for text and `(( ))` for numbers, each for its own job.
- **Doing arithmetic on text.** `(( port >= 1024 ))` with `port=abc` would not mean what it looks like. The script tests that the text is all digits first.
- **Building a command as one string.** The script keeps `curl_command` as a list, so no word is split or re-read.
- **Letting a script send without asking.** The default is a dry run; the real send needs `--run`, and a question unless `--yes`.
- **Pasting the dry-run line into a terminal.** It shows the words but not their quote marks. To send, use `--run`.
- **Expecting a message when there is no keyboard.** With input closed, the script exits with code 1 and says nothing more than the plan. Run it from a terminal, or add `--yes`.

## Check yourself

1. *What does `set -euo pipefail` do?* It stops the script on the first failing command, on a read of an unset variable and on a failure anywhere in a pipe.
2. *What happens if you run the script with only four arguments?* It is a dry run: it prints the plan and the command and sends nothing, with exit code 0.
3. *Why is the key written with extra quote marks in the header?* The contract requires the `Idempotency-Key` header to be a quoted string, so the script adds the quotes itself.
4. *Which exit code means a bad input, and which means "you said no"?* 2 for a bad input or wrong call, 1 for "you said no" or a missing `curl`.
5. *Why can the script not send to another computer?* The address in the URL is the fixed text `127.0.0.1`; only the port comes from the user, and it is checked to be between 1024 and 65535.

## Further reading

- The Bash reference manual (GNU Bash): <https://www.gnu.org/software/bash/manual/bash.html>
- Shell parameter expansion, arrays and conditional expressions are chapters of the same manual: <https://www.gnu.org/software/bash/manual/html_node/Arrays.html>
- The curl manual: <https://curl.se/docs/manpage.html>
- The Single Unix Specification, Shell Command Language (POSIX): <https://pubs.opengroup.org/onlinepubs/9799919799/utilities/V3_chap02.html>
- HTTP Semantics, RFC 9110: <https://www.rfc-editor.org/rfc/rfc9110>

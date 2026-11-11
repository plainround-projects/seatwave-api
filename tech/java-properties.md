# The `.properties` format and the settings files under `config/`

## What it is

A **properties file** is a plain text file of settings. Each setting is one line, `key=value`. The key is a name. The value is the text after the first `=`. A program reads the file at start-up and uses the values to decide how to run. Changing a setting means editing the text file; the program's code does not change and is not rebuilt.

The format comes from Java. The JDK class `java.util.Properties` reads it. This repository has five such files in `config/`, one for each program it can run.

## Why this repository uses it

Everything that may differ between runs lives in one file per program: the port, the database address, how long to wait for the payment provider, how many requests to fire in the retry storm. Two rules from the repository's README make the files strict:

- **Every key is required.** There are no defaults in the code. A missing key is a problem, never a silent fallback. This stops a program from running with a value nobody chose.
- **Every problem is reported at once.** If three keys are wrong, the program prints three lines and exits. It does not stop at the first.

The numbers that the contract publishes (500 items per page at most, 100 by default, a body of at most 1,024 bytes, a 300-second hold, 24 hours of key retention) are not settings. They are constants in `ContractLimits`, because changing one would change the contract.

## Where it appears

| File | Read by | Used for |
|---|---|---|
| `config/serve.properties` | `ServeCli` | `./gradlew -q serve --args='config/serve.properties'`: the API server. |
| `config/payment-stand-in.properties` | `PaymentStandInCli` | `./gradlew -q paymentStandIn ...`: the stand-in payment provider. |
| `config/retry-storm.properties` | `RetryStormCli` | `./gradlew -q retryStorm ...`: the retry storm. |
| `config/page-walk.properties` | `PageWalkCli` | `./gradlew -q pageWalk ...`: the pagination walk, with holds between pages. |
| `config/page-walk-inserts.properties` | `PageWalkCli` | The same walk, with new seats added between pages (Lab 2). |

All programs read the file with `SettingsReader.load` (UTF-8) and ask `SettingsReader` for typed values. The four API-shaped programs then call `ApiSettings.from`, which reads the common keys.

## How it works underneath

### The file format

The rules, from `java.util.Properties.load`:

- A line starting with `#` or `!` is a **comment**. It is ignored. A comment must be on its own line.
- A key is the text before the first `=`, `:` or white space. The value is the rest of the line. Spaces around the separator are dropped, and **trailing spaces stay part of the value**.
- A value is the whole rest of the line. `port=8087 # the port` has the value `8087 # the port`, which is not a number. A trailing comment on a value line is an error, not a comment. This repository puts every comment on its own line.
- A backslash at the end of a line continues the value on the next line.
- If a key appears twice, the last one wins.

I checked these rules with a small program on Java 25.0.2 (the third key shows a database address with a semicolon and an equals sign inside the value, which is fine because only the first separator counts):

```text
import java.io.StringReader;
import java.util.Properties;
import java.util.TreeMap;

public class Props {
  public static void main(String[] args) throws Exception {
    Properties p = new Properties();
    p.load(new StringReader("# c\nk=v\nh=x # not\nd=1\nd=2\nsp = a b \nc: z\n! bang\nurl=jdbc:h2:mem:t;LOCK_TIMEOUT=2000\nlong=a\\\n   b\n"));
    System.out.println(new TreeMap<>(p) + " sp=[" + p.getProperty("sp") + "]");
  }
}
```

```text
{c=z, d=2, h=x # not, k=v, long=ab, sp=a b , url=jdbc:h2:mem:t;LOCK_TIMEOUT=2000} sp=[a b ]
```

Both comment lines gave no key. `h` kept its `# not`. `d` is 2. `long` joined its two lines. `sp` kept its trailing space. `c: z` worked with a colon.

### How `SettingsReader` checks a value

`SettingsReader.text(key)` takes the value, strips the spaces at both ends, and records the problem `<key> is missing` if the key is absent or blank. The typed readers build on it:

| Method | Rule | Problem line |
|---|---|---|
| `intBetween(key, min, max)` | A whole number from min to max. | `<key> must be from <min> to <max>, but was <n>` or `<key> must be a whole number, but was <text>` |
| `millisBetween(key, min, max)` | Same, read as milliseconds into a `Duration`. | the same |
| `instant(key)` | An ISO-8601 UTC instant such as `2026-11-11T00:00:00Z`. | `<key> must be an ISO-8601 UTC instant such as 2026-11-11T00:00:00Z` |
| `amount(key)` | Digits, a point, exactly two digits (`80.00`). | `<key> must be an amount such as 80.00, but was <text>` |
| `matching(key, pattern, description)` | The whole value matches the pattern. | `<key> must be <description>, but was <text>` |

When a value is bad the reader records the problem and returns a harmless stand-in (the minimum, zero, the epoch), so reading goes on and every other problem is found in the same run. The caller must look at `problems()` before using any value.

`ApiSettings.from` also checks the two rules between keys: the lease must be longer than the connect and request timeouts together, and `version0.sunsetAt` must be after `version0.deprecatedAt`. Each is checked only when its keys were valid alone, and its line joins the same list as every other problem, so all of them appear in one run. I set both wrong and both lines were printed. The records' constructors check the same rules again, as a last line of defence for code that builds them directly.

I broke `serve.properties` four ways in a scratch copy (port 80, no `seed.sections`, a provider on `example.com`, a price of `80`):

```text
invalid: http.port must be from 1024 to 65535, but was 80
invalid: seed.sections is missing
invalid: payment.url must be http://127.0.0.1:<port>, but was http://example.com:8088
invalid: booking.seatPrice must be an amount such as 80.00, but was 80
```

All four in one run, then the exit status 2 that Gradle reports as `finished with non-zero exit value 2`.

## Walkthrough of this repository's use

### `config/serve.properties`

```properties
# The SeatWave API (Module 7, Section 8). Every key is required; there are no defaults in code.
# The contract's own limits (page sizes, body size, hold time, key retention) are not settings.
http.port=8087
```

- Line 1 says every key is required and there are no defaults in code. Line 2 says the contract's own limits are not settings.
- `http.port=8087` is the TCP port the API listens on, on `127.0.0.1` only. The reader accepts 1,024 to 65,535 (the ports below 1,024 need special rights). Change it and the address in every `curl` changes. If the port is in use, the server cannot start; `ServeCli` prints `serving failed: ...` and exits with status 1.

```properties
# Requests served at once; one more is refused with 503 and Retry-After (Module 4).
http.maxInFlight=64
http.retryAfterSeconds=1
```

- `http.maxInFlight=64` is how many requests the server works on at once, 1 to 10,000. One more is refused at once with `503` and a `Retry-After` header (Module 4: a fast refusal keeps the work already admitted fast). Lower it and the server sheds load sooner. Raise it and more database connections may be open at the same time, since each request uses its own.
- `http.retryAfterSeconds=1` is the number in that `Retry-After` header, 1 to 60. It changes only what the client is told to wait.

```properties
# A file database under build/, so a restart keeps holds, bookings and keys. LOCK_TIMEOUT bounds
# how long a request waits for a row another request is changing (two seconds, in milliseconds).
database.url=jdbc:h2:file:./build/data/seatwave-api;LOCK_TIMEOUT=2000
```

- The comment explains the choice of a file database under `build/`, so a restart keeps holds, bookings and keys, and what `LOCK_TIMEOUT` does: it bounds how long a request waits for a row another request is changing. Two seconds, in milliseconds.
- `database.url=jdbc:h2:file:./build/data/seatwave-api;LOCK_TIMEOUT=2000` is the H2 address. The pattern the reader accepts is `jdbc:h2:(mem|file):` followed by letters, digits, `_ . / ; = -`. Change `file:./build/data/seatwave-api` to `mem:name` and the data is lost at exit. Lower `LOCK_TIMEOUT` and a request that waits for a lock fails sooner with H2 error `50200`, which the API turns into a `503`. The file path is relative to the folder where Gradle runs the program.

```properties
# Module 1's demo event (20 sections of 1,000 seats) and 119 more events for the catalog's pages.
seed.events=120
seed.sections=20
seed.seatsPerSection=1000
```

- `seed.events=120`, `seed.sections=20` and `seed.seatsPerSection=1000` describe the demo data written into an **empty** database: 120 events (1 to 10,000), of which only event 1 has seats; 20 sections of 1,000 seats, so 20,000 seats (sections 1 to 20, seats 1 to 1,000). An existing database is never changed by them: `Database.seedIfEmpty` seeds only when there are no events. I ran the server with `seed.events=3` on an in-memory database and `GET /v1/events?limit=2` returned two events and a `nextCursor`; `GET /events` returned exactly three.

```properties
# The stand-in provider (./gradlew -q paymentStandIn). Module 2's confirm budget is 2,000 ms at p99,
# so the provider gets 1,500 ms; the lease must outlast connect plus request.
payment.url=http://127.0.0.1:8088
payment.connectTimeoutMillis=250
payment.requestTimeoutMillis=1500
payment.leaseMillis=5000
```

- `payment.url=http://127.0.0.1:8088` is where the provider is. The reader accepts only `http://127.0.0.1:<port>`: SeatWave refuses to call anything outside this machine. Its port must match `standIn.port` of the stand-in.
- `payment.connectTimeoutMillis=250` is how long to wait for the TCP connection. `payment.requestTimeoutMillis=1500` is how long to wait for the provider's whole reply; the comment says Module 2's confirm budget is 2,000 ms at the 99th percentile, so the provider gets 1,500 ms. In my session a confirm against a slow provider answered `503` after `time_total=1.517485s`, which is this value plus overhead. Each is 1 to 60,000 ms.
- `payment.leaseMillis=5000` is how long a confirm owns a seat while it pays. The comment says it must outlast connect plus request: 250 + 1,500 = 1,750 ms, less than 5,000. `ApiSettings.Payment` refuses a lease that is not longer than the two timeouts together. Set it to 1000 and the program prints `invalid: payment.leaseMillis must be longer than the connect and request timeouts together`. A short lease would let a second confirm take the seat over while the first is still waiting for the provider.

```properties
# Module 2's average ticket.
booking.seatPrice=80.00
booking.currency=USD
```

- `booking.seatPrice=80.00` is the price of one seat, the amount charged at confirm. It must be digits, a point and exactly two digits, and above zero. `80` is refused. `booking.currency=USD` is a three-capital-letter code. Both show up in the stand-in's ledger and in every booking reply (`"amount":"80.00","currency":"USD"`).

```properties
# Module 1's paths are deprecated today and stop answering in six months.
version0.deprecatedAt=2026-11-11T00:00:00Z
version0.sunsetAt=2027-05-11T00:00:00Z
serve.seconds=600
```

- `version0.deprecatedAt` and `version0.sunsetAt` are the dates announced for Module 1's old paths. They appear as headers on every old-path reply. I read them on the wire from `GET /events`: `Deprecation: @1794355200` (the deprecation date as Unix seconds after an at-sign, RFC 9745) and `Sunset: Tue, 11 May 2027 00:00:00 GMT` (an HTTP date, RFC 8594). The sunset must be after the deprecation date. Changing a date changes only the headers; the paths still answer after the sunset date (this lab does not turn them off).
- `serve.seconds=600` is how long the server runs before it stops itself, 1 to 3,600. `ServeCli` sleeps that long and then closes. Raise it for a longer session.

### `config/payment-stand-in.properties`

```properties
# The stand-in payment provider (Module 7, Section 8). It moves no money and listens on 127.0.0.1.
standIn.port=8088
# ok, charge_then_timeout (capture, then answer after slowReplyMillis) or fail (capture nothing, 503).
# Change it while it runs: curl -X POST 'http://127.0.0.1:8088/control/mode?mode=fail'
standIn.mode=ok
# Longer than SeatWave's payment.requestTimeoutMillis (1,500), so SeatWave gives up first.
standIn.slowReplyMillis=3000
serve.seconds=600
```

- `standIn.port=8088` is the port of the stand-in (1,024 to 65,535). It must equal the port in `payment.url` of `serve.properties`.
- `standIn.mode=ok` is the starting behavior: `ok`, `charge_then_timeout` or `fail`. The comment shows how to change it while the stand-in runs, with `curl -X POST 'http://127.0.0.1:8088/control/mode?mode=fail'`. Anything else is refused with `standIn.mode must be ok, charge_then_timeout or fail, but was <text>`.
- `standIn.slowReplyMillis=3000` is how long `charge_then_timeout` waits before it answers (1 to 60,000 ms). The comment: longer than SeatWave's `payment.requestTimeoutMillis` (1,500), so SeatWave gives up first. If you set it below 1,500, the answer arrives in time and the timeout case never happens.
- `serve.seconds=600` is how long the stand-in runs, 1 to 3,600.

### `config/retry-storm.properties`

```properties
# The retry storm (Module 7, Section 6 and Lab 3). The API and the stand-in provider run in the
# storm's own process, on 127.0.0.1, with an in-memory database that is gone when it ends.
storm.concurrency=100
storm.pairs=500
http.port=18087
http.maxInFlight=64
http.retryAfterSeconds=1
database.url=jdbc:h2:mem:retry-storm;LOCK_TIMEOUT=2000
seed.events=1
seed.sections=20
seed.seatsPerSection=1000
payment.url=http://127.0.0.1:18088
payment.connectTimeoutMillis=250
payment.requestTimeoutMillis=1500
payment.leaseMillis=5000
booking.seatPrice=80.00
booking.currency=USD
version0.deprecatedAt=2026-11-11T00:00:00Z
version0.sunsetAt=2027-05-11T00:00:00Z
```

- The comment: the API and the stand-in provider run in the storm's own process, on `127.0.0.1`, with an in-memory database that is gone when it ends.
- `storm.concurrency=100` is how many requests are fired at once (1 to 1,000). Scenario 1 sends one hold 100 times at once with one key, and scenario 3 has 100 fans want one seat.
- `storm.pairs=500` is how many hold-and-confirm pairs scenario 2 runs, each sent twice at once with its own key (1 to 18,000). 500 pairs means 2,000 requests. The pairs use the seats of all but the first two sections (`SECTIONS_NOT_FOR_PAIRS` is 2), so 20 sections of 1,000 seats leave 18,000, which is the cap.
- `http.port=18087`, `http.maxInFlight=64` and `http.retryAfterSeconds=1` are the same keys as in `serve.properties`, with a different port so the storm does not collide with a running server.
- `database.url=jdbc:h2:mem:retry-storm;LOCK_TIMEOUT=2000` is an in-memory database. `LOCK_TIMEOUT=2000` matters here: the 100 racers wait on one row, and the lock timeout is how long the last one may wait.
- `seed.events=1`, `seed.sections=20`, `seed.seatsPerSection=1000`: Module 1's single event with 20,000 seats.
- `payment.url=http://127.0.0.1:18088`: the port where the storm starts its own stand-in. The timeouts and the lease, `booking.seatPrice`, `booking.currency`, `version0.deprecatedAt` and `version0.sunsetAt` are the same as in `serve.properties` and mean the same. The storm does not read the version 0 dates, but `ApiSettings.from` requires them, so they must be present and valid.
- There is no `serve.seconds`: the storm ends when its work ends.

I ran it in a scratch copy with the two ports changed (28187 and 28188):

```text
scenario 1: one hold sent 100 times at once with one key
  final replies by status: {201=100}
scenario 3: one seat wanted by 100 fans with 100 keys
  final replies by status: {201=1, 409=99}
all requests: final statuses {201=2101, 409=99}; retries (503, or 409 in progress): 554; idempotency keys stored: 1101
```

### `config/page-walk.properties`

```properties
# The pagination consistency test (Module 7, Section 6 and Lab 2). The API runs in this process on
# 127.0.0.1, twice, each time on a fresh in-memory database of Module 1's 20,000 seats.
walk.pageSize=500
# Between pages: fans hold 25 random seats and the venue adds 5 seats at the end of random sections.
walk.holdsPerPage=25
walk.insertsPerPage=5
http.port=18089
http.maxInFlight=64
http.retryAfterSeconds=1
# Replaced by one in-memory database per walk; it must still be a valid H2 URL here.
database.url=jdbc:h2:mem:page-walk
seed.events=1
seed.sections=20
seed.seatsPerSection=1000
# No confirm runs in this walk, so no provider is started; the settings are still checked.
payment.url=http://127.0.0.1:18090
payment.connectTimeoutMillis=250
payment.requestTimeoutMillis=1500
payment.leaseMillis=5000
booking.seatPrice=80.00
booking.currency=USD
version0.deprecatedAt=2026-11-11T00:00:00Z
version0.sunsetAt=2027-05-11T00:00:00Z
```

- The comment: the pagination consistency test runs the API in this process on `127.0.0.1`, twice, each time on a fresh in-memory database of Module 1's 20,000 seats. (The first walk uses cursors, the second offsets, and the program compares them.)
- `walk.pageSize=500` is the page size of the walk (1 to 500, the contract's maximum).
- The comment above the next two keys: between pages, fans hold 25 random seats and the venue adds 5 seats at the end of random sections. `walk.holdsPerPage=25` is the number of holds between pages (0 to 1,000) and `walk.insertsPerPage=5` the number of new seats (0 to 1,000). More changes between pages make the offset walk skip more seats; the cursor walk stays exact. My run of the program showed `cursor ... skipped 0, repeated 0` and `offset ... skipped 345`.
- `http.port=18089`, `http.maxInFlight=64`, `http.retryAfterSeconds=1`: as before.
- `database.url=jdbc:h2:mem:page-walk`: the comment says it is replaced by one in-memory database per walk (`jdbc:h2:mem:page-walk-<name>`), but it must still be a valid H2 URL here, because the settings are checked before anything runs.
- The seed keys give 1 event of 20 sections of 1,000 seats.
- The comment before `payment.url`: no confirm runs in this walk, so no provider is started; the settings are still checked. That is why the payment keys, price, currency and version 0 dates are present: `ApiSettings.from` requires every key.

### `config/page-walk-inserts.properties`

This file is `page-walk.properties` with two numbers changed. Every other line is identical, so those lines are quoted above. The two lines that differ, quoted from this file:

```properties
walk.holdsPerPage=0
walk.insertsPerPage=25
```

- The comment above them says Lab 2: no holds, and after each page the venue adds 25 seats at the end of random sections. `walk.holdsPerPage=0` is allowed because the minimum is 0. `walk.insertsPerPage=25` adds seats. A new seat in a section the walk has already passed moves every later row down one place, so an offset walk, which counts rows, reads some rows twice. My run of this file's walk showed `offset ... repeated 500` and `cursor ... skipped 0, repeated 0`.

## Try it yourself

All commands run in a scratch copy of the repository. Copies of settings files go in a scratch folder, never in `config/`. Timings differ on every run.

**1. Check the properties rules.** Save the `Props.java` program above in a scratch folder and run `java Props.java`. The output is the line shown above.

**2. Break `serve.properties` several ways and see every problem at once.** Make a copy with `http.port=80`, no `seed.sections` line, `payment.url=http://example.com:8088` and `booking.seatPrice=80`, then:

```text
./gradlew -q serve --args='<path of your copy>'
```

```text
invalid: http.port must be from 1024 to 65535, but was 80
invalid: seed.sections is missing
invalid: payment.url must be http://127.0.0.1:<port>, but was http://example.com:8088
invalid: booking.seatPrice must be an amount such as 80.00, but was 80
```

Gradle then prints `finished with non-zero exit value 2`.

**3. Break a rule between two keys.** A copy with `payment.leaseMillis=1000`:

```text
invalid: payment.leaseMillis must be longer than the connect and request timeouts together
```

If you also set `version0.sunsetAt=2026-01-01T00:00:00Z`, a second line `invalid: version0.sunsetAt must be after version0.deprecatedAt` appears in the same run.

**4. See the old-path headers change with the dates.** Start the server with a copy of `serve.properties` (a free port, `mem:` database, a short `serve.seconds`) and run:

```text
curl -s -i http://127.0.0.1:<port>/events
```

```text
HTTP/1.1 200 OK
Sunset: Tue, 11 May 2027 00:00:00 GMT
Content-type: application/json
Deprecation: @1794355200
Content-length: 173
Link: </v1/events>; rel="successor-version"
```

(The `Date` header and the body are left out; the order of the headers is the server's.) Change `version0.sunsetAt` in the copy, restart, and the `Sunset` line follows.

**5. Run the stand-in with the other modes.** Edit a copy of `payment-stand-in.properties` to `standIn.mode=fail` (and a free port), start it, and `curl` a charge: `{"error":"provider failing on purpose"}` with `503`.

## Mistakes people make

- **A comment after a value.** `http.port=8087 # lab port` makes the value `8087 # lab port` and the reader says `http.port must be a whole number`. Put comments on their own line.
- **Trailing spaces.** Java keeps them in the value. This reader strips them, but another reader might not.
- **Expecting a default.** There is none. A missing key is an error. That is deliberate.
- **Changing the seed for an existing database.** `seed.*` is read only when the database has no events. To use new numbers, start with a new in-memory database or delete the file under `build/data/`.
- **Mixing up the two ports.** `payment.url` in `serve.properties` and `standIn.port` in `payment-stand-in.properties` must name the same port.
- **Setting the lease shorter than the timeouts.** The program refuses it, for a good reason: two confirms could pay for one seat.
- **Using the same port for two programs.** The second fails to start. Each settings file here uses its own ports: 8087 and 8088 for the lab session, 18087 and 18088 for the storm, 18089 for the walk.

## Check yourself

1. How does the reader treat `http.port=8087 # lab port`? (The whole text `8087 # lab port` is the value. Reading it as a number fails and the reader reports `http.port must be a whole number`.)
2. If the same key appears twice in a file, which value is used? (The last one.)
3. What happens when you delete a key from `serve.properties`? (The reader records `<key> is missing`, the program prints every problem it found, and exits with status 2.)
4. Why does `page-walk.properties` contain `payment.url`, when the walk never calls a provider? (Every key is required, and `ApiSettings.from` reads the payment keys for any program that starts an API. A missing key is an error, not a default.)
5. Why must `payment.leaseMillis` be longer than the connect and request timeouts together? (A lease shorter than the call would let a second confirm take the seat over while the first is still waiting for the provider.)

## Further reading

Official documentation, for the JDK this repository pins (Java 25):

- `java.util.Properties` and the exact file format (see the `load(Reader)` method): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- `java.nio.file.Files.newBufferedReader`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html
- `java.time.Instant.parse` (the format of `version0.deprecatedAt`): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Instant.html
- `java.math.BigDecimal` (the type of `booking.seatPrice`): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/math/BigDecimal.html
- H2 2.5.252 database URL settings, including `LOCK_TIMEOUT`: https://h2database.com/html/features.html
- RFC 9745, The Deprecation HTTP Response Header Field: https://www.rfc-editor.org/rfc/rfc9745
- RFC 8594, The Sunset HTTP Header Field: https://www.rfc-editor.org/rfc/rfc8594

# The OpenAPI contract (`openapi/`), line by line

## What it is

An **API** (application programming interface) is the set of requests a program accepts from other programs. SeatWave's API accepts, for example, "hold seat A-7 of event 1 for fan_7" and answers "done, here is your hold". The program that sends the request is the **client**. The program that answers is the **server**.

An **API contract** is a written, exact description of that conversation: which paths exist, which methods each accepts, which fields a request may carry, which status codes come back and what the body of each looks like. Both sides agree to it. A client author can write code from the contract without seeing the server. A server author can check the server against the contract.

**OpenAPI** is the most common format for such a contract. This repository uses OpenAPI 3.1.2. An OpenAPI document is a text file, written in **YAML** (or JSON). The same file can be read by a person, by a validator that checks the document is well formed, by a test that checks real replies against it, and by a tool that compares two versions of it.

## Why this repository uses it

The lesson of Module 7 is that the contract comes first. `openapi/seatwave-api.yaml` is the source of truth, and the build checks it in three directions: the document itself is valid and follows the house rules, every reply of the running service matches it, and a change cannot break a client of the released version. The folder holds the pieces for that last check and for the labs:

| File | Role |
|---|---|
| `openapi/seatwave-api.yaml` | The current contract, version 1.0.0. |
| `openapi/released/seatwave-api-1.0.0.yaml` | The copy of what was released. New work is compared against it. It is byte for byte the same as the current file today (checked with `diff`). |
| `openapi/examples/seatwave-api-1.1.0-additive.yaml` | A candidate next version whose change is safe. |
| `openapi/examples/seatwave-api-1.1.0-breaking.yaml` | A candidate next version whose changes break clients. |
| `openapi/examples/holds-entry-broken.yaml` | Lab 1: a first draft of one operation, with mistakes for the linter to find. |
| `openapi/examples/holds-entry.yaml` | Lab 1: the solution. |

## Where it appears

| File or code | What it does with the contract |
|---|---|
| `openapi/` (six files above) | The documents themselves. |
| `build.gradle.kts` tasks `lintContract` and `diffContract` | Run the checks by hand (`./gradlew -q lintContract --args='...'`). |
| `ContractLimits.java` | Constants for the numbers the contract publishes (500 per page, 1,024 bytes, 300 seconds, 24 hours). A test checks they match the document. |
| `HoldCommand.java`, `HoldId.java`, `IdempotencyKey.java` | Regular expressions that mirror the patterns of the schemas. |
| The tests under `src/test/java/.../contract/` | Validate the document, replies, and changes (see `tests-contract-walkthrough.md`). |

## How it works underneath

### YAML in five minutes

YAML is a format for nested data written with indentation. The rules this file uses:

- A **mapping** is a list of `key: value` pairs. A line like `title: SeatWave booking API` means the key `title` has the value `SeatWave booking API`.
- **Indentation** shows nesting, with spaces only (never tabs). Two spaces per level here. A key indented under another key belongs to it. Changing the indentation changes the meaning.
- A **sequence** (a list) is written with a dash: `- url: http://127.0.0.1:8087` is the first item of a list, and that item is itself a mapping.
- A **flow sequence** is a list on one line in square brackets: `required: [eventId, seat, customer]`. A **flow mapping** uses braces: `schemas: {}` is an empty mapping.
- A **scalar** is a single value: text, number, true or false. Text usually needs no quotes. Quotes are needed when the text could be read as something else or contains special characters: `'200'` is kept as text, because a bare `200` would be a number, and the status codes are text keys in OpenAPI. A pattern like `'^[A-T]$'` is quoted because of the `^` and the brackets.
- A **block scalar** `|` after a key means: the following indented lines are one multi-line text, line breaks kept. It is used for long descriptions.
- A `#` starts a **comment** (the example files use them).

### The shape of an OpenAPI document

```text
openapi:      the version of the OpenAPI format (3.1.2)
info:         title, version of THIS contract, summary, description, license
servers:      where the API lives (a base URL)
tags:         names that group operations
paths:        every path, and under it every method (an "operation")
components:   reusable pieces: parameters, headers, responses, schemas
```

- **Path**: an address such as `/v1/holds` or `/v1/holds/{holdId}`. The part in braces is a **path parameter**, a value the client puts in the address.
- **Operation**: one method on one path (`get`, `post`, `delete`). It has an `operationId` (a unique name, used by tools), a `summary`, `parameters`, maybe a `requestBody`, and `responses`.
- **Parameter**: a value that is not in the body. It has a `name`, a place (`in: path`, `query`, `header`) and a `schema`. A **query** parameter is the part after `?` in the address: `?limit=100`.
- **Request body**: what the client sends in a `POST`. It lists a media type (such as `application/json`) and a schema.
- **Response**: one entry per status code, with a description, maybe headers, and a body by media type.
- **Schema**: a description of a value, written in JSON Schema (OpenAPI 3.1 uses JSON Schema 2020-12). `type: object` with `properties` describes an object; `required: [a, b]` lists the properties that must be present; `type: string` with `pattern` says the text must match a regular expression; `enum` lists the allowed values; `minimum` and `maximum` bound a number; `format: date-time` marks a text as a timestamp; `additionalProperties: false` forbids any property not listed.
- **`$ref`** is a reference: `$ref: '#/components/schemas/Hold'` means "use the thing found at this path inside this same document". `#` is the document, then `/components/schemas/Hold` walks down the mapping keys. A reference lets one definition be used in many places, so a change is made once.

### Compatible and breaking changes

A change is **additive** (compatible) if every client written for the old contract still works. A change is **breaking** if some old client would fail. The test is always: think of a client built from the released document.

| Change | Verdict | Why |
|---|---|---|
| Add a new optional field to a response | additive | Old clients ignore fields they do not know. The contract says clients must. |
| Add a new operation or path | additive | Old clients never call it. |
| Add an optional field to a request | additive | Old clients simply do not send it. |
| Make a request field newly **required** | breaking | Old clients do not send it, and the server would refuse them. |
| Remove or rename a response field | breaking | Old clients read the old name and find nothing. |
| Make a response field that was always present optional | breaking | Old clients assume it is there. |

## Walkthrough of this repository's use

### `openapi/seatwave-api.yaml`

#### The header: `openapi`, `info`

```yaml
openapi: 3.1.2
info:
  title: SeatWave booking API
  version: 1.0.0
  summary: List events, page through seats, and hold, confirm and cancel seats, safely retried.
```

- `openapi: 3.1.2` is the version of the format, not of this API. 3.1 is the release that uses full JSON Schema.
- `info:` starts the block about this contract. `title` is its name. `version: 1.0.0` is the version of this contract (major.minor.patch). `summary` is one line (a field added in OpenAPI 3.1).

```yaml
  description: |
    The contract between SeatWave and every client. It is the source of truth: the build fails
    when this document is invalid and when the running service answers with anything it does not
    describe.

    Rules that hold for every operation:
    - Every error, in version 1, is a problem details body (RFC 9457) of media type
      application/problem+json.
    - Collections are paginated with an opaque cursor; the largest page is 500 items.
    - POST /v1/holds and POST /v1/bookings require an Idempotency-Key header (an RFC 9651
      String, as the IETF draft draft-ietf-httpapi-idempotency-key-header asks). A retry with the
      same key and the same body gets the first response again and does nothing new. The same key
      with a different body gets 422. A retry while the first request is still running gets 409.
      A key is kept for 24 hours after its first use; after that the key is forgotten and a request
      that reuses it is treated as new.
    - Changes inside version 1 are additive only: new operations, new optional request fields,
      new response fields. Clients must ignore response fields they do not know.
    - The unversioned paths of SeatWave version 0 (Module 1) still answer, unchanged, and are
      marked deprecated. Every reply on them carries Deprecation, Sunset and Link headers.
```

- `description: |` begins the long text. It is the contract in words, and it states the rules that hold for every operation.
- Every error of version 1 is a **problem details** body (RFC 9457): a JSON object with `type`, `title`, `status`, `detail`, with the media type `application/problem+json`.
- Collections use an **opaque cursor**: a text the server makes and the client sends back unchanged; the largest page is 500 items.
- `POST /v1/holds` and `POST /v1/bookings` require the `Idempotency-Key` header. The text gives the whole retry story: same key and same body gets the first response; same key and another body gets 422; a retry while the first is still running gets 409; a key is kept 24 hours and then forgotten.
- Changes inside version 1 are additive only. Clients must ignore response fields they do not know. This one sentence is what makes the additive change below safe.
- The unversioned paths of version 0 still answer, are marked deprecated, and carry `Deprecation`, `Sunset` and `Link` headers.

```yaml
  license:
    name: MIT
    identifier: MIT
```

- `license` has a `name` and an SPDX `identifier` (`MIT`), a 3.1 feature.

#### `servers` and `tags`

```yaml
servers:
  - url: http://127.0.0.1:8087
    description: The lab server; it binds to the loopback address only.
tags:
  - name: catalog
    description: Events and their seats.
  - name: booking
    description: Holds and bookings.
  - name: operations
    description: Health and metrics for the people who run SeatWave.
  - name: version-0
    description: The Module 1 paths, kept for old clients until their sunset date.
```

- `servers` is a list. One server: `http://127.0.0.1:8087` with a description. The address is the loopback address only: the lab server answers nobody outside the machine.
- `tags` is a list of names with descriptions. Operations list the tags they belong to; a documentation page groups them. The four are `catalog`, `booking`, `operations` and `version-0`.

#### `paths`: the catalog

```yaml
paths:
  /v1/events:
    get:
      tags: [catalog]
      operationId: listEvents
      summary: List events, soonest first, one page at a time.
      parameters:
        - $ref: '#/components/parameters/Limit'
        - $ref: '#/components/parameters/Cursor'
      responses:
        '200':
          description: One page of events. nextCursor is absent on the last page.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EventPage'
        '400':
          $ref: '#/components/responses/BadRequest'
        '503':
          $ref: '#/components/responses/Unavailable'
```

- `paths:` begins the list of addresses. `/v1/events:` is a path; `get:` is the method, and everything under it describes that one operation.
- `tags: [catalog]` is a flow sequence. `operationId: listEvents` is the unique name of the operation. `summary` is one line.
- `parameters:` is a list. Each item here is a `$ref` to a reusable parameter in `components`, `Limit` and `Cursor`, defined once and used in two operations.
- `responses:` is keyed by status code. `'200'` has a `description`, and `content` says which media type the body has (`application/json`) and `schema` points to `EventPage`. `'400'` and `'503'` are references to shared responses (`BadRequest`, `Unavailable`), which are problem details.

```yaml
  /v1/events/{eventId}/seats:
    get:
      tags: [catalog]
      operationId: listSeats
      summary: List one event's seats in seat-map order, one page at a time.
      description: |
        The order is section, then seat number, and it never changes, so a cursor can name the
        last seat a client has seen. Seats held or sold after a page was read keep their place:
        paging neither skips nor repeats a seat. A cursor is bound to the filters it was made
        with; using it with other filters is a 400.
      parameters:
        - $ref: '#/components/parameters/EventId'
        - name: section
          in: query
          description: Only seats in this section.
          schema:
            type: string
            pattern: '^[A-T]$'
        - name: state
          in: query
          description: Only seats in this state.
          schema:
            $ref: '#/components/schemas/SeatState'
        - $ref: '#/components/parameters/Limit'
        - $ref: '#/components/parameters/Cursor'
      responses:
        '200':
          description: One page of seats. nextCursor is absent on the last page.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SeatPage'
        '400':
          $ref: '#/components/responses/BadRequest'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/Unavailable'
```

- `/v1/events/{eventId}/seats` has a path parameter. The `EventId` reference defines `eventId`.
- The `description: |` block states the paging rule: the order is section then seat number and never changes; seats held or sold after a page was read keep their place, so paging neither skips nor repeats a seat; a cursor is bound to the filters it was made with.
- Two query parameters are written inline. `section`: `in: query`, a `string` with `pattern: '^[A-T]$'` (`^` start, `$` end, one letter A to T). `state`: a `$ref` to the `SeatState` schema, an enum.
- The responses add `'404'` (`NotFound`), because the event may not exist.

#### `paths`: holds and bookings

```yaml
  /v1/holds:
    post:
      tags: [booking]
      operationId: holdSeat
      summary: Hold one seat for 300 seconds.
      parameters:
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/HoldRequest'
      responses:
        '201':
          description: The seat is held. A retry with the same key returns this same hold.
          headers:
            Location:
              description: The new hold, /v1/holds/{holdId}.
              schema:
                type: string
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Hold'
        '400':
          $ref: '#/components/responses/BadRequest'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/Conflict'
        '413':
          $ref: '#/components/responses/TooLarge'
        '415':
          $ref: '#/components/responses/UnsupportedMediaType'
        '422':
          $ref: '#/components/responses/KeyReused'
        '503':
          $ref: '#/components/responses/Unavailable'
```

- `post:` on `/v1/holds` is the operation that holds a seat. It lists one parameter, the `Idempotency-Key` header.
- `requestBody:` with `required: true` says a body must be sent. `content` / `application/json` / `schema` points to `HoldRequest`.
- The `'201'` response has a `headers:` block: a `Location` header (the address of the new hold) with a text schema. Its body is a `Hold`.
- The error responses name every way the call can fail: `400` bad request, `404` no such seat, `409` seat taken or request still running, `413` body over 1,024 bytes, `415` body not JSON, `422` key reused with another body, `503` busy or provider down. Each is a `$ref`, so each error has one definition.

```yaml
  /v1/holds/{holdId}:
    get:
      tags: [booking]
      operationId: getHold
      summary: Read a hold and its state.
      parameters:
        - $ref: '#/components/parameters/HoldId'
      responses:
        '200':
          description: The hold.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Hold'
        '400':
          $ref: '#/components/responses/BadRequest'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/Unavailable'
    delete:
      tags: [booking]
      operationId: cancelHold
      summary: Release a hold. Idempotent by design, so it needs no key.
      description: |
        Cancelling a hold that is already cancelled or expired answers 204 again: the seat ends
        in the same state however many times the request arrives. A hold that became a booking
        cannot be cancelled here (409).
      parameters:
        - $ref: '#/components/parameters/HoldId'
      responses:
        '204':
          description: The hold is released (now or earlier).
        '400':
          $ref: '#/components/responses/BadRequest'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/Conflict'
        '503':
          $ref: '#/components/responses/Unavailable'
```

- `/v1/holds/{holdId}` has two operations, `get` (`getHold`) and `delete` (`cancelHold`). The path parameter `holdId` comes from `components/parameters/HoldId`, which is defined once and used by both.
- `delete` has the description of cancel: cancelling a hold that is already cancelled or expired answers 204 again, so no key is needed; a hold that became a booking is `409`.
- The `'204'` response has only a `description`: no `content`, because 204 means "no body".

```yaml
  /v1/bookings:
    post:
      tags: [booking]
      operationId: confirmBooking
      summary: Pay for a held seat and turn the hold into a booking.
      description: |
        The payment is taken through the payment provider with a provider key made from the hold,
        so one hold can be charged at most once, whatever the client retries and even after this
        API has forgotten the client's Idempotency-Key. If the provider times out or fails, the
        answer is 503 and nothing is stored under the key, so the client retries with the same key.
      parameters:
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/BookingRequest'
      responses:
        '201':
          description: The seat is paid for and booked. A retry with the same key returns this booking.
          headers:
            Location:
              description: The new booking, /v1/bookings/{bookingId}.
              schema:
                type: string
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Booking'
        '400':
          $ref: '#/components/responses/BadRequest'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/Conflict'
        '413':
          $ref: '#/components/responses/TooLarge'
        '415':
          $ref: '#/components/responses/UnsupportedMediaType'
        '422':
          $ref: '#/components/responses/KeyReused'
        '503':
          $ref: '#/components/responses/Unavailable'
```

- `/v1/bookings` `post` is the confirm. Its description explains the payment: it is taken with a provider key made from the hold, so one hold is charged at most once, even after the API forgot the client's key; if the provider times out or fails the answer is 503 and nothing is stored under the key, so the client retries with the same key.
- The request body is `BookingRequest`; the `'201'` body is a `Booking` with a `Location` header. The same list of error responses follows.

```yaml
  /v1/bookings/{bookingId}:
    get:
      tags: [booking]
      operationId: getBooking
      summary: Read a booking.
      parameters:
        - name: bookingId
          in: path
          required: true
          schema:
            $ref: '#/components/schemas/Uuid'
      responses:
        '200':
          description: The booking.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Booking'
        '400':
          $ref: '#/components/responses/BadRequest'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/Unavailable'
```

- `/v1/bookings/{bookingId}` `get` reads a booking. The path parameter is written inline here: `name`, `in: path`, `required: true` (a path parameter is always required), and a `schema` that is a `$ref` to `Uuid`.

#### `paths`: operations

```yaml
  /health:
    get:
      tags: [operations]
      operationId: health
      summary: Is this process able to answer?
      responses:
        '200':
          description: The process and its database answer.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Health'
        '503':
          $ref: '#/components/responses/Unavailable'
  /metrics:
    get:
      tags: [operations]
      operationId: metrics
      summary: Counters and latency histograms in the Prometheus text format.
      responses:
        '200':
          description: The metrics.
          content:
            text/plain:
              schema:
                type: string
```

- `/health` answers with a `Health` object. `/metrics` answers `text/plain` with a `type: string` schema: the Prometheus text format is plain text, not JSON. These two have no problem response for `400`, because they take no input.

#### `paths`: version 0

```yaml
  /events:
    get:
      tags: [version-0]
      operationId: listEventsVersion0
      deprecated: true
      summary: Module 1's event list, unpaginated, epoch milliseconds.
      responses:
        '200':
          description: Every event.
          headers:
            Deprecation:
              $ref: '#/components/headers/Deprecation'
            Sunset:
              $ref: '#/components/headers/Sunset'
            Link:
              $ref: '#/components/headers/SuccessorLink'
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/EventVersion0'
        '503':
          $ref: '#/components/responses/UnavailableVersion0'
```

- `/events` (no `/v1`) is Module 1's old list. `deprecated: true` is a standard flag that tells tools and readers this operation is going away.
- The `'200'` response lists three headers by reference: `Deprecation`, `Sunset`, `Link` (`SuccessorLink`), defined once under `components/headers`. The body is `type: array` whose `items` are `EventVersion0`: an array of objects, unpaginated.
- `'503'` is `UnavailableVersion0`, the old plain error shape.

```yaml
  /events/{eventId}/seats:
    get:
      tags: [version-0]
      operationId: listSeatsVersion0
      deprecated: true
      summary: Module 1's seat list for one section, at most 1,000 seats.
      parameters:
        - $ref: '#/components/parameters/EventId'
        - name: section
          in: query
          required: true
          schema:
            type: string
            pattern: '^[A-T]$'
      responses:
        '200':
          description: The section's seats.
          headers:
            Deprecation:
              $ref: '#/components/headers/Deprecation'
            Sunset:
              $ref: '#/components/headers/Sunset'
            Link:
              $ref: '#/components/headers/SuccessorLink'
          content:
            application/json:
              schema:
                type: array
                maxItems: 1000
                items:
                  $ref: '#/components/schemas/SeatVersion0'
        '400':
          $ref: '#/components/responses/BadRequestVersion0'
        '503':
          $ref: '#/components/responses/UnavailableVersion0'
```

- `/events/{eventId}/seats` is the old seat list. The `section` query parameter here has `required: true`: the old API required it. `maxItems: 1000` on the array is the old limit of 1,000 seats per section.

```yaml
  /holds:
    post:
      tags: [version-0]
      operationId: holdSeatVersion0
      deprecated: true
      summary: Module 1's hold, a form body and no idempotency key, so a retry after a lost reply gets 409 for its own hold.
      requestBody:
        required: true
        content:
          application/x-www-form-urlencoded:
            schema:
              type: object
              required: [eventId, seat, customer]
              properties:
                eventId:
                  type: string
                seat:
                  type: string
                customer:
                  type: string
      responses:
        '201':
          description: The seat is held.
          headers:
            Deprecation:
              $ref: '#/components/headers/Deprecation'
            Sunset:
              $ref: '#/components/headers/Sunset'
            Link:
              $ref: '#/components/headers/SuccessorLink'
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/HoldVersion0'
        '400':
          $ref: '#/components/responses/BadRequestVersion0'
        '409':
          $ref: '#/components/responses/ConflictVersion0'
        '413':
          $ref: '#/components/responses/BadRequestVersion0'
        '503':
          $ref: '#/components/responses/UnavailableVersion0'
```

- `/holds` (old) takes a **form body**: `application/x-www-form-urlencoded`, the format of an HTML form (`eventId=1&seat=A-7&customer=fan_7`). The schema is an inline `type: object` with `required: [eventId, seat, customer]` and three string properties. Form values are always text, so `eventId` is a `string` here.
- The summary says what is wrong with it: no idempotency key, so a retry after a lost reply gets 409 for its own hold.
- `'413'` reuses `BadRequestVersion0`, since Module 1 answered a too-large body in the old error shape.

```yaml
  /bookings:
    post:
      tags: [version-0]
      operationId: confirmBookingVersion0
      deprecated: true
      summary: Module 1's confirm, a form body; since Module 2 it takes the payment too.
      requestBody:
        required: true
        content:
          application/x-www-form-urlencoded:
            schema:
              type: object
              required: [holdId]
              properties:
                holdId:
                  type: string
      responses:
        '201':
          description: The seat is booked.
          headers:
            Deprecation:
              $ref: '#/components/headers/Deprecation'
            Sunset:
              $ref: '#/components/headers/Sunset'
            Link:
              $ref: '#/components/headers/SuccessorLink'
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BookingVersion0'
        '400':
          $ref: '#/components/responses/BadRequestVersion0'
        '409':
          $ref: '#/components/responses/ConflictVersion0'
        '413':
          $ref: '#/components/responses/BadRequestVersion0'
        '503':
          $ref: '#/components/responses/UnavailableVersion0'
```

- `/bookings` (old) is the old confirm, a form with `holdId`. Since Module 2 it takes the payment too. Its `'201'` returns a `BookingVersion0` and the three deprecation headers.

#### `components`: parameters

```yaml
components:
  parameters:
    EventId:
      name: eventId
      in: path
      required: true
      schema:
        type: integer
        format: int64
        minimum: 1
    HoldId:
      name: holdId
      in: path
      required: true
      schema:
        $ref: '#/components/schemas/Uuid'
```

- `components:` holds the reusable pieces. Nothing here is part of the API until a `$ref` points to it.
- `EventId`: a path parameter, an `integer` with `format: int64` (a 64-bit number) and `minimum: 1`.
- `HoldId`: a path parameter whose schema is the `Uuid` schema.

```yaml
    Limit:
      name: limit
      in: query
      description: Items per page, from 1 to 500. Without it, 100.
      schema:
        type: integer
        minimum: 1
        maximum: 500
        default: 100
    Cursor:
      name: cursor
      in: query
      description: The nextCursor of the previous page, unchanged. Opaque to clients.
      schema:
        type: string
        pattern: '^[A-Za-z0-9_-]{1,200}$'
    IdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      description: |
        A String in the RFC 9651 syntax, so written with double quotes, for example
        "8e03978e-40d5-43e8-bc93-6894a57f9324". Inside the quotes: 1 to 64 letters, digits,
        hyphens or underscores. A random UUID per user action is the recommended choice.
      schema:
        type: string
        pattern: '^"[A-Za-z0-9_-]{1,64}"$'
```

- `Limit`: a query parameter, an integer from 1 to 500 with `default: 100`. These numbers are in `ContractLimits` as `DEFAULT_PAGE_LIMIT` and `MAX_PAGE_LIMIT`.
- `Cursor`: a query parameter, text matching `^[A-Za-z0-9_-]{1,200}$` (URL-safe characters only).
- `IdempotencyKey`: `in: header`, `required: true`. Its description gives the syntax: an RFC 9651 String, written with double quotes, for example `"8e03978e-40d5-43e8-bc93-6894a57f9324"`. The pattern `'^"[A-Za-z0-9_-]{1,64}"$'` includes the quotes. This is what `IdempotencyKey.fromHeader` checks.

#### `components`: headers

```yaml
  headers:
    Deprecation:
      description: When this path was deprecated, an RFC 9745 date, for example @1798761600.
      schema:
        type: string
        pattern: '^@[0-9]+$'
    Sunset:
      description: When this path stops answering, an RFC 8594 HTTP date.
      schema:
        type: string
    SuccessorLink:
      description: The version 1 replacement, as an RFC 8288 link with rel="successor-version".
      schema:
        type: string
```

- Three response headers. `Deprecation`: an RFC 9745 date written like `@1798761600` (a Unix time in seconds after an at-sign), with `pattern: '^@[0-9]+$'`. `Sunset`: when the path stops answering, an RFC 8594 HTTP date. `SuccessorLink`: the replacement, an RFC 8288 link with `rel="successor-version"`.

#### `components`: responses

```yaml
  responses:
    BadRequest:
      description: The request breaks the contract (a field, a header, a cursor); errors names each one.
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    NotFound:
      description: No such event, seat, hold or booking.
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    Conflict:
      description: |
        The request is valid but the state does not allow it: the seat is taken, the hold expired
        or was cancelled or became a booking, or a request with the same Idempotency-Key is still
        running. The type tells which.
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    TooLarge:
      description: The body is over 1,024 bytes.
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    UnsupportedMediaType:
      description: The body is not application/json.
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    KeyReused:
      description: This Idempotency-Key was already used with a different body.
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
```

- Each shared response has a description and a body of `application/problem+json` whose schema is `Problem`.
- `BadRequest` (400): the request breaks the contract; the `errors` list names each field. `NotFound` (404). `Conflict` (409): the request is valid but the state does not allow it, and the problem `type` tells which. `TooLarge` (413). `UnsupportedMediaType` (415). `KeyReused` (422).

```yaml
    Unavailable:
      description: Busy, the database is unreachable, or the payment provider did not answer. Retry after Retry-After seconds, with the same Idempotency-Key.
      headers:
        Retry-After:
          description: Seconds to wait before a retry.
          schema:
            type: integer
            minimum: 1
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    BadRequestVersion0:
      description: Module 1's error body.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorVersion0'
    ConflictVersion0:
      description: Module 1's error body.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorVersion0'
    UnavailableVersion0:
      description: Module 1's error body.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorVersion0'
```

- `Unavailable` (503) adds a `Retry-After` header, an integer of at least 1: the seconds to wait. Its description tells the client to retry with the same Idempotency-Key.
- The three `...Version0` responses have `application/json` and `ErrorVersion0`: Module 1's old `{"error": "..."}` shape.

#### `components`: schemas

```yaml
  schemas:
    Uuid:
      type: string
      pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    SeatLabel:
      type: string
      description: Section letter, a hyphen, then the seat number, for example A-7.
      pattern: '^[A-T]-[1-9][0-9]{0,3}$'
    SeatState:
      type: string
      enum: [available, held, sold]
```

- `Uuid`: text matching the lowercase UUID pattern (the same as `HoldId.UUID_TEXT`).
- `SeatLabel`: section letter, hyphen, seat number from 1 to 9999 (the same as `HoldCommand.SEAT_LABEL`).
- `SeatState`: an enum with three words a client sees in a seat list: `available`, `held`, `sold`.

```yaml
    Event:
      type: object
      required: [eventId, name, startsAt]
      properties:
        eventId:
          type: integer
          format: int64
        name:
          type: string
        startsAt:
          type: string
          format: date-time
    EventPage:
      type: object
      required: [items]
      properties:
        items:
          type: array
          maxItems: 500
          items:
            $ref: '#/components/schemas/Event'
        nextCursor:
          type: string
```

- `Event`: an object with `required: [eventId, name, startsAt]`; `startsAt` is `format: date-time`, an RFC 3339 timestamp such as `2026-12-11T01:04:53Z`.
- `EventPage`: `required: [items]`. `items` is an array of at most 500 `Event`s. `nextCursor` is optional text: absent on the last page.

```yaml
    Seat:
      type: object
      required: [seat, section, state]
      properties:
        seat:
          $ref: '#/components/schemas/SeatLabel'
        section:
          type: string
          pattern: '^[A-T]$'
        state:
          $ref: '#/components/schemas/SeatState'
    SeatPage:
      type: object
      required: [items]
      properties:
        items:
          type: array
          maxItems: 500
          items:
            $ref: '#/components/schemas/Seat'
        nextCursor:
          type: string
```

- `Seat` has `seat` (a `SeatLabel`), `section` and `state` (a `SeatState`), all required. `SeatPage` is the page of seats, built like `EventPage`.

```yaml
    HoldRequest:
      type: object
      additionalProperties: false
      required: [eventId, seat, customer]
      properties:
        eventId:
          type: integer
          format: int64
          minimum: 1
        seat:
          $ref: '#/components/schemas/SeatLabel'
        customer:
          type: string
          pattern: '^[A-Za-z0-9_-]{1,64}$'
```

- `HoldRequest` has `additionalProperties: false`: a field that is not listed (`price`, in the README's session) is refused with 400. `required: [eventId, seat, customer]`. `eventId` is an `int64` of at least 1; `seat` is a `SeatLabel`; `customer` matches `^[A-Za-z0-9_-]{1,64}$`.

```yaml
    Hold:
      type: object
      required: [holdId, eventId, seat, state, expiresAt]
      properties:
        holdId:
          $ref: '#/components/schemas/Uuid'
        eventId:
          type: integer
          format: int64
        seat:
          $ref: '#/components/schemas/SeatLabel'
        state:
          type: string
          enum: [held, confirming, confirmed, cancelled, expired]
        expiresAt:
          type: string
          format: date-time
```

- `Hold` is the reply: `holdId`, `eventId`, `seat`, `state` and `expiresAt` are all required. The `state` enum lists five words in lower case: `held`, `confirming`, `confirmed`, `cancelled`, `expired`. They mirror `HoldState`. Note this enum differs from `SeatState`: a seat is `available`, `held` or `sold`; a hold goes through five states.

```yaml
    BookingRequest:
      type: object
      additionalProperties: false
      required: [holdId]
      properties:
        holdId:
          $ref: '#/components/schemas/Uuid'
```

- `BookingRequest`: only `holdId`, with `additionalProperties: false`.

```yaml
    Booking:
      type: object
      required: [bookingId, holdId, eventId, seat, paymentId, amount, currency, confirmedAt]
      properties:
        bookingId:
          $ref: '#/components/schemas/Uuid'
        holdId:
          $ref: '#/components/schemas/Uuid'
        eventId:
          type: integer
          format: int64
        seat:
          $ref: '#/components/schemas/SeatLabel'
        paymentId:
          type: string
        amount:
          type: string
          description: A decimal amount with two digits after the point, for example 80.00.
          pattern: '^[0-9]+\.[0-9]{2}$'
        currency:
          type: string
          pattern: '^[A-Z]{3}$'
        confirmedAt:
          type: string
          format: date-time
```

- `Booking` has eight required fields. `amount` is a **string**, not a number: `pattern: '^[0-9]+\.[0-9]{2}$'` means digits, a point, exactly two digits (`80.00`). A string keeps the exact decimal; a JSON number could be turned into a floating-point value by a client. `currency` matches `^[A-Z]{3}$`. The `required` list names all eight properties.

```yaml
    Problem:
      type: object
      description: RFC 9457 problem details. Clients act on type and status; title and detail are for people.
      required: [type, title, status]
      properties:
        type:
          type: string
          format: uri-reference
        title:
          type: string
        status:
          type: integer
          minimum: 400
          maximum: 599
        detail:
          type: string
        instance:
          type: string
          format: uri-reference
        errors:
          type: array
          description: One entry per field that broke the contract.
          items:
            type: object
            required: [pointer, detail]
            properties:
              pointer:
                type: string
                description: A JSON pointer into the body, or the name of a header or a query parameter.
              detail:
                type: string
```

- `Problem` is RFC 9457 problem details. `required: [type, title, status]`. `type` is `format: uri-reference` (the address that names the kind of problem, such as `https://seatwave.example/problems/seat-unavailable`). `status` is an integer from 400 to 599. `detail` and `instance` are optional. The description says clients act on `type` and `status`; `title` and `detail` are for people.
- `errors` is an array of objects, each with a `pointer` (a JSON pointer into the body, or the name of a header or query parameter) and a `detail`. This is how the 400 reply names every bad field.

```yaml
    Health:
      type: object
      required: [status]
      properties:
        status:
          type: string
          enum: [up]
```

- `Health` has one required property `status` whose only allowed value is `up`.

```yaml
    EventVersion0:
      type: object
      required: [eventId, name, startsAt]
      properties:
        eventId:
          type: integer
        name:
          type: string
        startsAt:
          type: integer
          description: Epoch milliseconds.
    SeatVersion0:
      type: object
      required: [seat, state]
      properties:
        seat:
          type: string
        state:
          $ref: '#/components/schemas/SeatState'
    HoldVersion0:
      type: object
      required: [holdId, expiresInSeconds]
      properties:
        holdId:
          type: string
        expiresInSeconds:
          type: integer
    BookingVersion0:
      type: object
      required: [bookingId]
      properties:
        bookingId:
          type: string
    ErrorVersion0:
      type: object
      required: [error]
      properties:
        error:
          type: string
```

- The version 0 shapes. `EventVersion0` has `startsAt` as an `integer` (epoch milliseconds) where version 1 has a date-time text. `SeatVersion0` has just `seat` (plain text) and `state`. `HoldVersion0` has `holdId` and `expiresInSeconds`. `BookingVersion0` has `bookingId`. `ErrorVersion0` has `error`. They are kept exactly as Module 1 answered.

### `openapi/released/seatwave-api-1.0.0.yaml`

This file is the contract as released. It has 733 lines, the same as the current one, and `diff openapi/seatwave-api.yaml openapi/released/seatwave-api-1.0.0.yaml` prints nothing. Every line of it is quoted above. It exists because the current file will change: when work on 1.1.0 begins, the current file is edited and this one stays frozen. `BreakingChangeTest` compares the two on every build, so an edit that breaks a released client fails the build before it is shipped. When 1.1.0 is released, a copy named `seatwave-api-1.1.0.yaml` would be added and the check would compare against that.

### `openapi/examples/seatwave-api-1.1.0-additive.yaml`

This file has 736 lines: the current contract plus 3 lines (and a new version number). `diff openapi/released/seatwave-api-1.0.0.yaml openapi/examples/seatwave-api-1.1.0-additive.yaml` prints these differences:

```text
4c4
<   version: 1.0.0
---
>   version: 1.1.0
560a561,563
>         venue:
>           type: string
>           description: Added in 1.1.0. Optional, so clients built on 1.0.0 ignore it.
```

The lines of the file that differ, quoted from the file itself:

```yaml
  version: 1.1.0
```

```yaml
        venue:
          type: string
          description: Added in 1.1.0. Optional, so clients built on 1.0.0 ignore it.
```

- The first line is the version of the contract moving from 1.0.0 to 1.1.0. A new feature that old clients can ignore is a **minor** version in semantic versioning.
- The three lines add a property `venue` to the `Event` schema (it sits right after `startsAt`). It is not in the `required` list of `Event`, so it is optional. This is **additive**: a client built from 1.0.0 gets events with one extra field and, because the contract says clients ignore unknown response fields, nothing changes for it. A client built from 1.1.0 can read the venue.
- I ran the comparison:

```text
released:  openapi/released/seatwave-api-1.0.0.yaml
candidate: openapi/examples/seatwave-api-1.1.0-additive.yaml
operations: 0 new, 0 removed, 0 changed
openapi-diff: compatible
house rules: compatible
verdict: compatible, every released client still works
```

### `openapi/examples/seatwave-api-1.1.0-breaking.yaml`

This file also has 736 lines. `diff openapi/released/seatwave-api-1.0.0.yaml openapi/examples/seatwave-api-1.1.0-breaking.yaml` prints:

```text
4c4
<   version: 1.0.0
---
>   version: 1.1.0
574c574
<       required: [seat, section, state]
---
>       required: [seat, section, status]
581c581
<         state:
---
>         status:
597c597
<       required: [eventId, seat, customer]
---
>       required: [eventId, seat, customer, channel]
607a608,610
>         channel:
>           type: string
>           enum: [web, app, partner]
```

The lines that differ, quoted from the file itself:

```yaml
  version: 1.1.0
```

```yaml
      required: [seat, section, status]
```

```yaml
        status:
```

```yaml
      required: [eventId, seat, customer, channel]
```

```yaml
        channel:
          type: string
          enum: [web, app, partner]
```

- Line 4: the version is again 1.1.0. A breaking change under a minor number is itself a smell: a change that breaks clients belongs in a new major version, such as `/v2`, so clients move on purpose.
- Lines 574 and 581 are in the `Seat` schema. The property `state` is renamed to `status`, and the `required` list follows it. This is **breaking**, for two reasons that the tool reports separately. A client built from 1.0.0 reads `state` from every seat, and the new replies have no `state`: the field "was removed". And the old `required` promised that `state` is always present: it is "no longer always present". A rename is a removal plus an addition, and the removal breaks the old client. The `SeatState` enum is still used by the query parameter, so the filter keeps working, which makes the rename easy to miss.
- Lines 597 and 608 to 610 are in the `HoldRequest` schema. A new property `channel`, with an enum of three values, is added and also put in the `required` list. This is **breaking**: a client built from 1.0.0 sends `{"eventId":1,"seat":"A-7","customer":"fan_7"}` with no `channel`, and the new server would refuse it. Had `channel` been added without being required, it would have been additive.
- I ran the comparison:

```text
released:  openapi/released/seatwave-api-1.0.0.yaml
candidate: openapi/examples/seatwave-api-1.1.0-breaking.yaml
operations: 0 new, 0 removed, 1 changed
  no changes   POST   /v1/holds                    request incompatible responses no changes
openapi-diff: breaking
house rules: 3 breaking change(s)
  GET /v1/events/{eventId}/seats 200.items[]: field state is no longer always present
  GET /v1/events/{eventId}/seats 200.items[]: field state was removed
  POST /v1/holds request: field channel is newly required
verdict: BREAKING, released clients would fail
```

The command ends with exit status 3, which Gradle reports as a failed task; its footer is not shown here.

### `openapi/examples/holds-entry-broken.yaml` (Lab 1, the first draft)

```yaml
# Lab 1: a first draft of the POST /v1/holds entry with four mistakes for lintContract to find.
openapi: 3.1.2
info:
  title: SeatWave holds (Lab 1, first draft)
  version: 1.0.0
paths:
  /v1/holds:
    post:
      operationId: holdSeat
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [eventId, seat, customer]
              properties:
                eventId:
                  type: integer
                seat:
                  type: string
```

- This is a complete, small OpenAPI document with a single operation: `POST /v1/holds`. The comment says it holds four mistakes for `lintContract` to find.
- `info` has a `title` and a `version`. `paths` has one path with a `post`. It has an `operationId` but no `parameters` at all.
- `requestBody` is required and has an **inline** schema (written in place, not by reference): an object with `required: [eventId, seat, customer]` but `properties` for only `eventId` and `seat`. `customer` is required and never defined, and `eventId` has no `format` or `minimum`.

```yaml
      responses:
        '201':
          description: Held.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Hold'
        '409':
          description: Seat taken.
          content:
            application/json:
              schema:
                type: object
                properties:
                  error:
                    type: string
components:
  schemas: {}
```

- The `'201'` response points to `#/components/schemas/Hold`, but the `components` block at the bottom is `schemas: {}`, an empty mapping. The reference points at nothing.
- The `'409'` response has an inline schema, `application/json` with a plain `error` string, not `application/problem+json`.
- The bottom `components:` block with `schemas: {}` exists only so that the document is syntactically complete.

I ran the linter on it:

```text
openapi/examples/holds-entry-broken.yaml: 5 problem(s)
  parser: attribute paths.'/v1/holds'(post).responses.201.content.'application/json'.schema.#/components/schemas/Hold is missing
  parser: Could not find /components/schemas/Hold in contents of #/components/schemas/Hold
  rule: POST /v1/holds 409 must be application/problem+json Problem
  rule: POST /v1/holds creates something, so it must require Idempotency-Key
  rule: POST /v1/holds request: required field customer is not defined
```

The four mistakes are: the reference to a schema that does not exist (the parser reports it as two lines, so five lines in all), the 409 that is not a problem details body, the missing `Idempotency-Key`, and `customer`, which is required but never defined in `properties`. The parser accepts that last one, because a `required` name with no definition is valid OpenAPI; the house rule in `ContractRules` that every required field must be defined catches it. The linter exits with status 3.

### `openapi/examples/holds-entry.yaml` (Lab 1, the solution)

```yaml
# Lab 1 (easy), the solution: the contract entry for POST /v1/holds alone, with its success,
# conflict and error responses. Check it with: ./gradlew -q lintContract --args='openapi/examples/holds-entry.yaml'
openapi: 3.1.2
info:
  title: SeatWave holds (Lab 1)
  version: 1.0.0
paths:
  /v1/holds:
    post:
      operationId: holdSeat
      summary: Hold one seat for 300 seconds.
      parameters:
        - name: Idempotency-Key
          in: header
          required: true
          description: An RFC 9651 String, new for each user action, kept for 24 hours.
          schema:
            type: string
            pattern: '^"[A-Za-z0-9_-]{1,64}"$'
```

- The header comment says how to check it: `./gradlew -q lintContract --args='openapi/examples/holds-entry.yaml'`.
- The first parameter is the `Idempotency-Key` header, written inline: `in: header`, `required: true`, the same pattern as the full contract. This fixes mistake 3.

```yaml
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [eventId, seat, customer]
              properties:
                eventId:
                  type: integer
                  format: int64
                  minimum: 1
                seat:
                  type: string
                  pattern: '^[A-T]-[1-9][0-9]{0,3}$'
                customer:
                  type: string
                  pattern: '^[A-Za-z0-9_-]{1,64}$'
```

- The request body now has `additionalProperties: false`, all three properties, `format: int64` and `minimum: 1` for `eventId`, and the patterns of the real contract for `seat` and `customer`.

```yaml
      responses:
        '201':
          description: Held. A retry with the same key gets this same hold.
          headers:
            Location:
              schema:
                type: string
          content:
            application/json:
              schema:
                type: object
                required: [holdId, eventId, seat, state, expiresAt]
                properties:
                  holdId:
                    type: string
                  eventId:
                    type: integer
                  seat:
                    type: string
                  state:
                    type: string
                    enum: [held]
                  expiresAt:
                    type: string
                    format: date-time
        '400':
          $ref: '#/components/responses/Problem'
        '409':
          $ref: '#/components/responses/Problem'
        '422':
          $ref: '#/components/responses/Problem'
        '503':
          $ref: '#/components/responses/Problem'
```

- The `'201'` response has a `Location` header and an inline body schema, with the five properties and `enum: [held]` for `state`: a new hold is always `held`. This fixes mistake 1: the response no longer points at a missing schema.
- The four errors, `'400'`, `'409'`, `'422'`, `'503'`, point at one shared response, `Problem`.

```yaml
components:
  responses:
    Problem:
      description: RFC 9457 problem details.
      content:
        application/problem+json:
          schema:
            type: object
            required: [type, title, status]
            properties:
              type:
                type: string
                format: uri-reference
              title:
                type: string
              status:
                type: integer
              detail:
                type: string
```

- `components` has `responses` with one entry, `Problem`: `application/problem+json`, an object with `required: [type, title, status]` and the properties of RFC 9457. This fixes mistake 2: all four errors share the one definition. Reuse through `$ref` is the point of `components`.

I ran the linter on it, and on the two documents of the contract:

```text
openapi/examples/holds-entry.yaml: valid, every rule passed
openapi/seatwave-api.yaml: valid, every rule passed
openapi/released/seatwave-api-1.0.0.yaml: valid, every rule passed
```

## Try it yourself

All commands run in a scratch copy of the repository. The first run of Gradle downloads files; timings differ on every run.

**1. Lint the contract.**

```text
./gradlew -q lintContract --args='openapi/seatwave-api.yaml openapi/released/seatwave-api-1.0.0.yaml'
```

```text
openapi/seatwave-api.yaml: valid, every rule passed
openapi/released/seatwave-api-1.0.0.yaml: valid, every rule passed
```

**2. Lint the broken draft.** Run the same task on `openapi/examples/holds-entry-broken.yaml`. It prints the four problem lines above and Gradle reports a failed task (exit status 3).

**3. Compare two versions.**

```text
./gradlew -q diffContract --args='openapi/released/seatwave-api-1.0.0.yaml openapi/examples/seatwave-api-1.1.0-additive.yaml'
```

It ends with `verdict: compatible, every released client still works`. With `openapi/examples/seatwave-api-1.1.0-breaking.yaml` it prints the three house-rule lines and `verdict: BREAKING, released clients would fail`.

**4. Break the contract yourself.** Copy `openapi/seatwave-api.yaml` to a scratch folder. Change the line `maximum: 500` of the `Limit` parameter to `maximum: 5000` and lint the copy:

```text
<path of your copy>: 2 problem(s)
  rule: GET /v1/events returns a page, so limit needs a maximum of at most 500
  rule: GET /v1/events/{eventId}/seats returns a page, so limit needs a maximum of at most 500
```

The page rule is reported once for each operation that uses the shared parameter. A second experiment: delete the `- $ref: '#/components/parameters/Cursor'` line of `/v1/events`. The linter says `rule: GET /v1/events returns a page, so it needs a cursor parameter`. Both runs end with exit status 3 and a failed Gradle task.

**5. See that the released file is the current file.**

```text
diff openapi/seatwave-api.yaml openapi/released/seatwave-api-1.0.0.yaml
```

It prints nothing and exits 0.

## Mistakes people make

- **Renaming a field and calling it a small change.** A rename is a removal plus an addition. The removal breaks old clients (the 1.1.0 breaking example).
- **Adding a required request field.** Old clients do not send it. Add it as optional, or start a new major version.
- **Describing errors by hand in every operation.** The descriptions drift apart. Define each error once in `components/responses` and point at it with `$ref`.
- **Sending money as a JSON number.** A floating-point value cannot hold 0.10 exactly. This contract uses a string with a pattern (`"80.00"`).
- **Leaving the contract and the code to drift.** Here the build checks every reply of the running service against the document, so a drift fails a test. The constants in `ContractLimits` are checked against the document too.
- **Editing the released copy.** Its job is to stay frozen so the next version can be compared with it.
- **Trusting indentation by eye.** A key that is indented one level too far silently moves under another key. Validate the file with a tool, as `lintContract` does.
- **Forgetting that `additionalProperties: false` rejects unknown fields.** It is right for requests (typos are caught); it would be wrong for responses, where clients must be free to ignore new fields.

## Check yourself

1. What does `$ref: '#/components/schemas/Hold'` mean? (Use the definition found under `components`, then `schemas`, then `Hold` in this same document.)
2. Why is adding the optional response field `venue` safe, and adding the required request field `channel` not? (Old clients ignore extra response fields. They do not send a new required request field, so the server would refuse them.)
3. Why is renaming `state` to `status` breaking when both are plain text? (A client built from 1.0.0 reads `state` and finds nothing, because the field was removed.)
4. Why is `'200'` written with quotes? (Status codes are keys that must be text. A bare `200` would be a number.)
5. What does `additionalProperties: false` do in `HoldRequest`? (It makes the server refuse a request body that has any property not listed, such as `price`.)

## Further reading

Official specifications, for the versions this repository pins:

- OpenAPI Specification 3.1.2: https://spec.openapis.org/oas/v3.1.2.html
- JSON Schema, draft 2020-12 (the schema dialect of OpenAPI 3.1): https://json-schema.org/draft/2020-12/json-schema-core and https://json-schema.org/draft/2020-12/json-schema-validation
- YAML 1.2.2: https://yaml.org/spec/1.2.2/
- RFC 9457, Problem Details for HTTP APIs: https://www.rfc-editor.org/rfc/rfc9457
- RFC 9651, Structured Field Values for HTTP: https://www.rfc-editor.org/rfc/rfc9651
- RFC 8594, The Sunset HTTP Header Field: https://www.rfc-editor.org/rfc/rfc8594
- RFC 9745, The Deprecation HTTP Response Header Field: https://www.rfc-editor.org/rfc/rfc9745
- RFC 8288, Web Linking: https://www.rfc-editor.org/rfc/rfc8288
- RFC 3339, Date and Time on the Internet: https://www.rfc-editor.org/rfc/rfc3339
- Semantic Versioning 2.0.0: https://semver.org/spec/v2.0.0.html
- The IETF draft for the Idempotency-Key header: https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/

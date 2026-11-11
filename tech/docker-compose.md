# Docker Compose and YAML (the optional `compose.yaml`)

## What it is

**Docker** runs programs in **containers**. A container is a sealed, lightweight box that holds one program together with the exact files it needs, so it runs the same everywhere. A container is created from an **image**, a read-only package. Images are stored in a **registry** such as Docker Hub and are named `name:tag`, where the tag picks a version.

**Docker Compose** reads a text file that describes one or more containers and runs them with one command (`docker compose up`, or `docker compose run` for a single service). The file is written in **YAML**, a format built from indentation. Two spaces under a key mean "this belongs to that key". There are three building blocks: `key: value` pairs, **lists** (lines that start with `- `, or items in square brackets on one line) and **comments** (`#` to the end of the line). Tabs are not allowed for indentation.

**This path was not run on the verification machine.** Docker was not running there, and the brief for this module says not to start it. Everything below comes from reading the file and the README, not from running a container. The README records the failed check: `docker info` printed `ERROR: Cannot connect to the Docker daemon`. What I did check without Docker is that the file is valid YAML: I loaded it with the SnakeYAML 2.5 jar from this repository's Gradle cache, and it parsed into one service named `lint` with the keys described below.

## Why this repository uses it

The build checks the contract with the OpenAPI parser, the house rules and the contract tests, and none of them needs a container. The Compose file adds a **second, independent linter** for readers who have Docker: Redocly CLI, which has its own rule set. The README marks it "optional" and "[not run]".

## Where it appears

| Path | What it does |
| --- | --- |
| `compose.yaml` | The only Compose file: one service, `lint`. |
| `openapi/` | Mounted read-only into the container as `/spec`. The linter reads `seatwave-api.yaml` from it. |
| `README.md` | Section "Docker Compose (optional)" gives the command `docker compose run --rm lint`. |

## How it works underneath

1. `docker compose run --rm lint` reads `compose.yaml` in the current folder and finds the service called `lint`.
2. It pulls the image named in `image:` from the registry if it is not on the machine yet, and checks that the image's digest is the pinned one.
3. It creates one container, mounts the volume, applies the security settings and starts the container with the given command-line arguments. `--rm` removes the container when it ends.
4. The container's program prints to your terminal and exits. Its exit code becomes the command's exit code.
5. A **bind mount** shows a file or folder of your machine inside the container. The suffix `:ro` makes it read-only inside.

An **image digest** is a fingerprint (a SHA-256 hash) of the exact image content. A tag such as `2.77.0` is a label that its owner could move to a different image later. A digest cannot move: a changed image has a different digest. Writing `name:tag@sha256:...` means "this tag, and I insist on exactly this content", so a pulled image that differs is refused.

A **Linux capability** is one small piece of the all-powerful administrator right (for example "change file owners" or "open a raw network socket"). A container normally gets a default set of them.

## Walkthrough of this repository's use

### The comment block (lines 1 to 6)

```yaml
# Optional: a second, independent linter for the contract, in a container. The build's own check
# (the Java parser, the house rules and the contract tests) needs no container; this adds Redocly's
# rule set for readers who have Docker. The tag and its digest were checked on Docker Hub on
# 11 November 2026 (Redocly CLI 2.77.0, pushed 9 November 2026). It reads the repository's
# openapi/ folder read-only, opens no port and needs no network once the image is pulled.
# Run it with: docker compose run --rm lint
```

These are comments. They say: the layer is optional; the build's own check (the Java parser, the house rules and the contract tests) needs no container; the tag and digest were checked on Docker Hub on 11 November 2026 (Redocly CLI 2.77.0, pushed 9 November 2026); the container reads the repository's `openapi/` folder read-only, opens no port and needs no network once the image is pulled; and the command to run it is `docker compose run --rm lint`.

### The service (lines 7 to 10)

```yaml
services:
  lint:
    image: redocly/cli:2.77.0@sha256:9f2213a2765ce9de36484ac2825bcf255c17db66e7b47909f8fbf189aba06b41
    command: ["lint", "/spec/seatwave-api.yaml"]
```

- `services:` starts the list of containers. Everything indented below it is a service.
- `lint:` is the name of the one service. The name is yours. `docker compose run --rm lint` uses it.
- `image: redocly/cli:2.77.0@sha256:9f22...b06b41` has three parts. `redocly/cli` is the image name on Docker Hub (the organization and the repository). `:2.77.0` is the tag, the version of Redocly CLI. `@sha256:...` is the digest pin explained above. Both the tag and the digest are written, so a reader sees the version, and Docker enforces the exact content. The comment at the top says when both were checked.
- `command: ["lint", "/spec/seatwave-api.yaml"]` is the list of arguments handed to the program that the image starts. The square brackets are YAML's one-line list. The program is Redocly CLI; `lint` is its command that checks a document against a rule set, and `/spec/seatwave-api.yaml` is the file to check, as seen **inside** the container. It exists there because of the next lines.

### The folder, the network and the file system (lines 11 to 14)

```yaml
    volumes:
      - ./openapi:/spec:ro
    network_mode: none
    read_only: true
```

- `volumes:` lists the folders shared with the container.
- `- ./openapi:/spec:ro` is a bind mount in the form `host-path:container-path:options`. `./openapi` is the repository's `openapi` folder (the path is relative to the Compose file). `/spec` is where it appears in the container. `:ro` makes it read-only. The linter can read the contract and cannot change it, even by mistake.
- `network_mode: none` gives the container no network at all, not even the loopback to the host. The linter works on a local file, so it needs none. A tool that cannot reach a network cannot send your files anywhere.
- `read_only: true` makes the container's own root file system read-only. The program can write nowhere inside the container. (I could not run it to see whether Redocly CLI accepts that; it is a point to check when the file is first run on a machine with Docker.)

### The privileges (lines 15 to 18)

```yaml
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
```

- `cap_drop:` followed by `- ALL` removes every Linux capability from the container. A linter needs none of them.
- `security_opt:` passes security options to Docker. `- no-new-privileges:true` is one list item. It looks like a key and a value because of the colon, but there is no space after the colon, so YAML reads it as a single piece of text. The option tells the kernel that a program in the container can never gain more rights than it started with, for example through a program that carries the set-user-id flag.

Together the last six keys follow the rule of least privilege: the container can read one folder, write nothing, reach nothing and do nothing special.

## Try it yourself

Docker was not running on the verification machine, so none of the Docker commands below was run. These are the commands a reader with Docker would use.

1. Check that the file is valid, without starting anything:

```
docker compose config
```

   Expected (not run): the file printed back in a normal form, with the paths made absolute.

2. Run the linter:

```
docker compose run --rm lint
```

   Expected (not run): Redocly CLI prints its findings for `/spec/seatwave-api.yaml` and a summary line, and the command's exit code is 0 when there are no errors. The first run pulls the image, so it needs a network once.

3. Read the file with line numbers, which needs only a terminal:

```
cat -n compose.yaml
```

   You see the 18 lines quoted above.

4. Check that the file parses as YAML, using the Java library the build already has (this one I ran):

```
java -cp "$(find ~/.gradle/caches/modules-2 -name 'snakeyaml-2.5.jar' | head -1)" Y.java compose.yaml
```

   where `Y.java` is a program of a few lines that prints `new Yaml().load(...)` of the file. The output was `{services={lint={image=redocly/cli:2.77.0@sha256:9f22..., command=[lint, /spec/seatwave-api.yaml], volumes=[./openapi:/spec:ro], network_mode=none, read_only=true, cap_drop=[ALL], security_opt=[no-new-privileges:true]}}}`: one service, `command` and the two others as lists, and `network_mode` and `read_only` as plain values.

## Mistakes people make

- **Tabs for indentation.** YAML forbids them. The `.editorconfig` file asks for two spaces in `*.yaml` (`editorconfig.md`).
- **Using only a tag.** A tag can move. This file adds the digest.
- **Mounting a folder read-write when the tool only reads.** The `:ro` suffix prevents changes.
- **Leaving the default capabilities.** `cap_drop: - ALL` removes them all.
- **Reading `no-new-privileges:true` as a key.** It is one text item. A key needs a space after the colon.
- **Treating an optional layer as proof.** This file was not run for these notes. The build's own checks are the proof.

## Check yourself

1. *What is a container image?* A read-only package that holds a program and the files it needs. A container is a running copy of it.
2. *What does `@sha256:...` after a tag do?* It pins the exact content, so a moved tag cannot change what runs.
3. *What does `network_mode: none` do?* It gives the container no network.
4. *What does `:ro` mean in `./openapi:/spec:ro`?* The folder is read-only inside the container.
5. *Was this file run on the verification machine?* No. Docker was not running, and the notes say so.

## Further reading

- The Compose file reference: <https://docs.docker.com/reference/compose-file/>
- `docker compose run`: <https://docs.docker.com/reference/cli/docker/compose/run/>
- Image digests: <https://docs.docker.com/reference/cli/docker/image/pull/#pull-an-image-by-digest-immutable-identifier>
- Redocly CLI, the `lint` command: <https://redocly.com/docs/cli/commands/lint>
- The YAML 1.2 specification: <https://yaml.org/spec/1.2.2/>

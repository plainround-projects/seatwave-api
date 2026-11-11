# Git: `.gitignore` and `.gitattributes`

## What it is

**Git** records the history of a folder of files. A **repository** is that folder plus its history. A file Git records is **tracked**. Git also lets you declare which files it should leave alone and how it should treat the line endings of the rest. Two plain-text files do that, and both sit at the root of the repository: `.gitignore` (leave these alone) and `.gitattributes` (treat these files in this way).

A **line ending** is the invisible character that ends a line of text. Unix systems (macOS, Linux) use one character, **LF** (line feed, `\n`). Windows uses two, **CRLF** (carriage return then line feed, `\r\n`).

## Why this repository uses it

Gradle creates two folders while it works, `build/` and `.gradle/`, and the editor and the operating system add more files. None belongs in the history. The scripts and runs here add more: the retry script writes `build/retry-hold/reply.json` and `build/retry-hold/audit.log` (`bash-retry-hold.md`), and they stay out of the history too. And two scripts need fixed line endings to run: `gradlew` needs LF, `gradlew.bat` needs CRLF, whoever checks them out and on whatever system.

## Where it appears

| Path | What it does |
| --- | --- |
| `.gitignore` | Eight patterns for files Git must not track. |
| `.gitattributes` | Two rules for line endings. |
| `.editorconfig` | The editor-side counterpart (`editorconfig.md`). |

## How it works underneath

1. `git status` and `git add` look at each file. If the path matches a pattern in `.gitignore`, Git skips it. The rule only affects files that are not tracked yet; a file already committed stays tracked.
2. Pattern rules: a `/` at the end means "a folder", `*` matches any run of characters, a name without a slash matches at any depth, `#` starts a comment, a blank line is ignored.
3. `.gitattributes` has the form `pattern attribute attribute`. Git reads it when it **adds** a file to the history and when it **checks out** a file into your folder.
4. `text` says "this is text; normalize its line endings". `eol=lf` or `eol=crlf` says which ending to write into the working folder on checkout. Inside the repository, Git always stores text with plain LF.

## Walkthrough of this repository's use

### `.gitignore`, line by line

```text
.gradle/
build/
out/
*.log
.DS_Store
.idea/
*.iml
.env
```

- `.gradle/` is Gradle's project cache folder. The slash at the end means the folder.
- `build/` is where Gradle writes compiled classes, test results and reports (`gradle.md`, `jacoco.md`), and where the retry script writes its `reply.json` and `audit.log`. They can be rebuilt or rerun, so they stay out of the history.
- `out/` is the output folder of IntelliJ IDEA and similar editors.
- `*.log` matches any file whose name ends in `.log`, such as the `audit.log` that `scripts/retry-hold.sh` writes (it is inside `build/` too, so two patterns cover it).
- `.DS_Store` is a folder-settings file that macOS Finder creates.
- `.idea/` is IntelliJ IDEA's project settings folder.
- `*.iml` matches IntelliJ module files.
- `.env` is the usual name for a file of secrets and local settings. Ignoring it keeps a password from being committed by accident. The repository has none, and the README lists no secrets.

I checked each with `git check-ignore -v`; for example `build/` is matched by `.gitignore:2:build/`. After running a build, `git status --short --ignored` shows `!! .gradle/` and `!! build/`, the two folders marked as ignored.

### `.gitattributes`, line by line

```text
gradlew text eol=lf
*.bat text eol=crlf
```

- `gradlew text eol=lf`: the file named `gradlew` is text and is checked out with LF endings, on every operating system. A shell script with CRLF endings fails on macOS and Linux (`posix-shell.md`).
- `*.bat text eol=crlf`: every file ending in `.bat` is text and is checked out with CRLF endings, which Windows batch files need (`windows-batch.md`).

On this Mac, `git ls-files --eol` confirms what Git does. The `i/` column is the form stored in the repository, `w/` is the form in my working folder:

```
i/lf    w/lf    attr/text eol=lf      	gradlew
i/lf    w/crlf  attr/text eol=crlf    	gradlew.bat
i/lf    w/lf    attr/                 	build.gradle.kts
```

`gradlew.bat` is stored with LF and written with CRLF; `build.gradle.kts` has no rule and stays LF.

## Try it yourself

All of these ran in a scratch copy of the repository that had been built once.

1. See which pattern ignores a path:

```
git check-ignore -v build/ .gradle/ foo.log .env .idea/ out/ x.iml .DS_Store build/retry-hold/reply.json
```

   Output: one line per path, for example `.gitignore:2:build/	build/`, `.gitignore:1:.gradle/	.gradle/`, `.gitignore:4:*.log	foo.log`, `.gitignore:8:.env	.env` and `.gitignore:2:build/	build/retry-hold/reply.json`. (The paths need not exist.)

2. See that a normal path is not ignored:

```
git check-ignore -v src/main/Main.java; echo "exit=$?"
```

   Output: nothing, then `exit=1`.

3. See the ignored folders that exist:

```
git status --short --ignored
```

   Output after a build: `!! .gradle/` and `!! build/`.

4. See the line-ending state of three files:

```
git ls-files --eol gradlew gradlew.bat build.gradle.kts
```

   Output: the three lines shown above (Git may list them in a different order).

5. Check that a log file inside `build/` is covered twice:

```
git check-ignore -v build/retry-hold/audit.log
```

   Output: `.gitignore:2:build/	build/retry-hold/audit.log`. Git prints only the first pattern that matches, here the `build/` rule, even though `*.log` also matches.

## Mistakes people make

- **Adding a pattern for a file that is already tracked.** Git keeps tracking it. Remove it from the index first (`git rm --cached file`).
- **Committing `build/`.** It is large and changes on every build, and it holds the experiment's output. The pattern prevents it.
- **Committing a `.env` file.** It would publish secrets. The pattern prevents it, and secrets should never be in the repository anyway.
- **Ignoring the wrapper jar** (a `*.jar` pattern would do it). This repository does not, because the wrapper needs the jar (`gradle-wrapper.md`).
- **Editing `gradlew` on Windows and committing CRLF.** The `eol=lf` rule normalizes it.
- **Expecting `.gitattributes` to change files already checked out.** Run `git add --renormalize .` or check the files out again.

## Check yourself

1. *Which file stops Git from tracking `build/`?* `.gitignore`, with the pattern `build/`.
2. *What does the `/` at the end of `.gradle/` mean?* It matches only a folder.
3. *Why does `gradlew` have `eol=lf`?* A shell script with CRLF endings fails on Unix.
4. *Why does `*.bat` have `eol=crlf`?* Windows batch files need Windows line endings.
5. *What is stored inside the repository for a text file?* The text with plain LF endings; the attribute decides what is written on checkout.

## Further reading

- gitignore patterns: <https://git-scm.com/docs/gitignore>
- gitattributes (`text`, `eol`): <https://git-scm.com/docs/gitattributes>
- `git check-ignore` and `git ls-files`: <https://git-scm.com/docs/git-check-ignore>, <https://git-scm.com/docs/git-ls-files>

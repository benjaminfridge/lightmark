# Lightmark

A Markdown reader for the Light Phone III, with highlights.

Reads `.md`, `.markdown` and `.txt`, set in pages. Swipe to turn a page. Press and hold
then drag to highlight; press and hold a highlight to remove it. Nothing leaves the
device and there is nothing to refresh.

## Status

Early. The reader, the parser and highlighting work. Receiving documents over WiFi is
written but switched off — see [docs/HOLDBACK.md](docs/HOLDBACK.md).

## Building

The Light SDK is a git submodule pinned to a release tag, which is what Light's build
server resolves. Clone with it:

```bash
git clone --recurse-submodules <this repo>
```

If you already cloned without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

Then:

```bash
./gradlew :tool:assembleDebug
```

### Toolchain

- **A JDK 17 installation must be present.** The SDK's own Gradle plugin requests a
  Java 17 toolchain by exact language version, so a newer JDK alone will not do — the
  build fails with "Cannot find a Java installation ... matching {languageVersion=17}".
  Gradle itself can run on 17 or 21.
- **Android SDK with platform 36** (`compileSdk = 36`) and build-tools 36.
- Android Studio is not needed to build, though it is the easiest way to run the emulator.

On macOS:

```bash
brew install openjdk@17
brew install --cask android-commandlinetools
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

If Gradle cannot find the 17 toolchain, point it at your JDKs in
`~/.gradle/gradle.properties` (machine-specific, so it does not belong in this repo):

```properties
org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home,/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Note the `libexec/openjdk.jdk/Contents/Home` suffix. Homebrew's `openjdk@17` prefix is
not itself a valid JDK home — Gradle will accept it and then Kotlin fails with "No class
roots are found in the JDK path", which is a confusing way to learn this.

`local.properties` needs `sdk.dir` set to your Android SDK; it is gitignored.

## Running it

**On a phone, over USB.** Enable Developer Mode on the
[dashboard](https://dashboard.thelightphone.com) under Settings → Account, then:

```bash
./gradlew :tool:installDebug
adb shell am start -n com.benfridge.lightmark/com.thelightphone.sdk.LightActivity
```

**On a phone, over WiFi.** Requires the Tool Manager in Settings on the device. Upload
your signing key's SHA-256 fingerprint under Developer Keys and a token
(`openssl rand -hex 32`) under Authentication, then:

```bash
./gradlew :tool:uploadTool -Pdevice.ip=192.168.1.42 -Pdevice.token=<your hex token>
```

**On the LightOS emulator.** Swap `serverPackage` in `tool/lighttool.toml` to the
commented emulator line, then build with the alternate-server flag:

```bash
./gradlew :tool:installDebug -DlightSdk.allowAltServerPackage=true
```

The emulator is not the phone: fonts, density, input and CPU all differ, so check
anything that touches layout or gestures on real hardware before trusting it.

## Adding documents

Bundled documents live in `tool/src/main/assets/documents/`, listed one per line in
`index.txt`. Your own documents go in the tool's shared directory:

```bash
adb push notes.md /sdcard/
adb shell run-as com.benfridge.lightmark cp /sdcard/notes.md files/shared/documents/
```

### Books are folders

A loose file in `documents/` is one document. A **folder** of files is a book, and its
files are its chapters:

```
documents/
  notes.md                      <- a document
  ways-of-being/                <- a book
    00-introduction.md
    01-the-wild-places.md
    02-...
```

That is how a converted PDF naturally lands, and it keeps a 150-page book from arriving
as thirty equal rows in the library. It also keeps the reader responsive: the measuring
pass is eager and whole-document, so a chapter measures in milliseconds where a whole
book would freeze the first frame for seconds.

Chapters are ordered by filename, compared **naturally** — `2-x.md` sorts before
`10-x.md`, so zero-padding is optional. Names become titles, so
`01-the-wild-places.md` reads as "01 the wild places".

Filenames are load-bearing: highlights are keyed on `shared:<folder>/<file>`, so renaming
a chapter after highlighting it orphans those highlights. Settle the naming first.

PDFs are better converted first. The only PDF renderer available to a tool returns a
page as a bitmap with no text layer, so there is nothing to select and nothing to
reflow:

```bash
pandoc paper.pdf -t markdown -o paper.md
```

## Tests

```bash
./gradlew :tool:testDebugUnitTest
```

62 tests covering the parser, pagination, the highlight-range maths and the merge
planner. All of it is pure logic on the JVM; see the note below on why that is the only
kind of test this platform can run.

Two invariants the tests exist to protect:

- every block's `start`/`end` indexes correctly into `Document.plainText`, because
  highlights are offsets into that string — if it drifts, every saved highlight lands on
  the wrong words;
- pagination loses and duplicates no lines, and a page's start offset resolves back to
  that same page.

### Why there are no UI tests

Instrumented tests need a device. Headless Compose UI tests need Robolectric, and
Robolectric is not on the SDK's dependency allow-list — `validateDeclaredDependencies`
in the Light Gradle plugin filters on `isCanBeDeclared && !isInternalConfig` and never
calls `isTestConfig`, while the resolved-graph check does skip test configurations. A
Compose test does compile and run, then fails in every case at
`RobolectricIdlingStrategy.getHasRobolectricFingerprint` with a null
`android.os.Build.FINGERPRINT`. Worth an upstream issue: having the declared-dependency
check skip test configurations too would open this up, and the resolved check already
establishes that as the intent.

## Exporting highlights

Every highlight, as one Markdown file: one `##` heading per document, each highlight a
blockquote. Notion, Obsidian and Readwise all ingest that without a custom importer.

Trigger it from the bottom of the library list — it covers the whole shelf, not the
chapter you are in, which is also why it is not in the reader (the reader has two
gestures and keeping it that way is the point).

The file is written to the tool's shared directory:

```bash
adb shell run-as com.benfridge.lightmark cat \
  files/shared/highlights/lightmark-highlights.md > highlights.md
```

Highlights store their own text, copied in when the highlight is made. That is
deliberate: `start`/`end` offsets only mean anything against the exact source text they
were taken from, so regenerating a chapter file from a PDF would otherwise shift every
offset in it and the export would quietly produce the wrong words. Storing the words
makes the export independent of the source files.

That directory is named for a Tool Manager job key, so when `tool-manager-provider` works
on a shipping LightOS the same file becomes a browser download with no change to the
export code. There is no forward-to-email path and cannot be one yet —
[docs/HOLDBACK.md](docs/HOLDBACK.md) explains why.

## Permissions

Lightmark declares none of its own — `permissions = []` in `lighttool.toml` — and its
code makes no network calls; nothing under `tool/src/main` references an HTTP client.

The built APK nevertheless lists these, and a reviewer will see them:

```
INTERNET  ACCESS_NETWORK_STATE  CAMERA  VIBRATE
WAKE_LOCK  FOREGROUND_SERVICE  RECEIVE_BOOT_COMPLETED
```

They arrive transitively from `:sdk:client`, which every tool depends on: Ktor and
OkHttp bring `INTERNET`, ML Kit's barcode scanner brings `CAMERA` (and ~880KB of
`.tflite` models), media3 and WorkManager bring the service and boot permissions. The
SDK's own source notes this is expected — "a bare platform permission says nothing about
what the tool asked for (any transitive dependency can declare one)" — which is why the
`permissions` list in `lighttool.toml` is the statement of intent, not the manifest.

Trimming the unused SDK dependencies is worth doing before submission; another developer
reported cutting about 22MB from their APK that way. For reference, this tool currently
builds to 79MB debug and 25MB release (R8 minified and resource-shrunk).

## Design notes

**The reader has two gestures and no chrome.** Swipe turns the page; press and hold then
drag highlights. A plain tap does nothing, deliberately. Leaving is the hardware back
key, which the SDK already routes through `goBack()` — so there is no top bar spending
two of the screen's thirty-one vertical grid units on every page.

**Swiping carries across chapter boundaries.** Past the last page of a chapter the next
one opens at its first page; back from the first page reopens the previous one at its
last. The chapter is swapped in place rather than pushed onto the back stack, so back
always returns to the chapter list however far you have read, instead of unwinding one
chapter at a time. A loose document is simply a book of one.

**A book remembers which chapter you were in**, separately from each chapter's own
character offset. Splitting a book gives every chapter a position but leaves the book
without one, and that single value is what makes a shelf of files behave like something
you were reading. The hairline still measures the chapter, not the book — chapters differ
in length, so a book-wide fraction would be quietly wrong.

**Pages, not scroll.** This panel is slower to redraw than a smartphone's. A page turn
is a single repaint where scrolling is a continuous smear, which is why e-readers
paginate. The swipe is discrete rather than following the finger, for the same reason:
one repaint per turn. Note that Readwise Reader defaults to scrolling on phones — it is
running on a 120Hz OLED, so that decision does not transfer.

**Pagination is line-granular.** A paragraph can give lines 0–7 to one page and 8–12 to
the next, as a book does. Two typographic rules apply: a heading is never split across a
page, and a heading is pushed to the next page if its body could not then follow it.
Both only apply when the page already has content, so neither can stall progress.

**Position is a character offset.** Not a pixel and not a page number: both shift
whenever the text reflows, and an activity recreation
([light-sdk#225](https://github.com/lightphone/light-sdk/issues/225)) is enough to
invalidate a pixel. The page is derived from the offset, so a re-measure keeps your
place.

**Highlights are character ranges too**, which is why they stay on the right words after
a reflow. They are drawn by inverting the text rather than washing it in colour: LightOS
gives a tool a background, a foreground and a dimmer foreground, so there is no colour to
wash with. Inversion is what a highlighter means on a monochrome screen, and it survives
a theme switch unchanged. The in-progress selection underlines instead, so you can see
what you are about to commit without it looking committed.

**Removal reuses the gesture you already learned.** Press and hold on a highlight to
remove it — no button, no menu, no mode. Highlights are the only durable data this tool
creates, and word-snapped dragging on a 3.92" screen will occasionally misfire, so
create-without-remove was not defensible. Starting a new highlight from inside an
existing one is given up in exchange, which costs nothing because overlapping ranges
merge anyway.

**One hairline, no numbers.** How far through you are is a real reading need, so it is
shown as a single-pixel rule along the bottom edge filling left to right. A page count
or percentage invites checking; a filling line is read without being looked at.

**Gestures are arbitrated explicitly.** Both start with a finger down in the same place,
so `readerGestures` races the long-press timer against movement rather than stacking two
detectors: move first and it is a swipe, stay still past the timeout and it is a
selection. Two independent detectors would both claim a press-then-drag and turn the page
mid-highlight.

**The renderer drops to Compose's `Text`.** `LightText` takes a `String` and exposes no
text layout, so it cannot style a sub-range or hit-test a tap. The renderer uses Compose's
`Text` directly but takes its `TextStyle` from `LightThemeTokens.typography`, so it stays
on-style while getting `AnnotatedString` and `onTextLayout`. Body text is `paragraphWide`
rather than `paragraph` — 1.30 leading and 0.02em tracking against 1.25 and none, which
is the difference between a UI label and something you read for ten minutes.

**Slices are positioned absolutely.** `layoutPage` computes each slice's y from the same
arithmetic the measuring pass used, and the renderer places slices at those positions
rather than stacking them in a Column. The hit-tester relies on measurement and render
agreeing; a highlight landing one line off is worse than none.

**The Markdown parser is hand-written.** No Markdown library is on the SDK's dependency
allow-list. The supported subset is deliberate: headings, paragraphs, lists, blockquotes,
code, rules, and inline bold/italic/code/links. Tables, nested lists, reference links,
raw HTML and footnotes are out.

### Known limits of this model

- **A highlight cannot cross a page boundary.** Releasing at the page edge commits what
  is selected. Auto-advancing mid-drag on a slow panel is where this goes wrong; two
  adjacent highlights merge anyway, since touching ranges are absorbed.
- **There is no highlight review or export yet.** A list screen is the right eventual
  home for both, and is also where copy-out will have to live given
  [light-sdk#45](https://github.com/lightphone/light-sdk/issues/45).

## Known platform constraints

| Constraint | Effect here |
|---|---|
| `tool-manager-provider` unreachable on LightOS 582 | WiFi document upload is held back |
| `buildDatabase()` takes no migrations ([light-sdk#227](https://github.com/lightphone/light-sdk/issues/227)) | The Room schema must stay at version 1 until there is a migration path |
| Config change recreates the activity ([light-sdk#225](https://github.com/lightphone/light-sdk/issues/225)) | Position is a character offset and the page is derived from it, so a recreation re-paginates without losing your place |
| No copy/paste in text components ([light-sdk#45](https://github.com/lightphone/light-sdk/issues/45)) | Exporting highlights as text is not yet possible on-device |

## Licence

Not yet chosen. The Tool Library requires tools to be open source, so this needs one
before submission.

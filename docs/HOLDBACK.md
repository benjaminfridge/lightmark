# What is held back, and why

## WiFi document upload (`tool-manager-provider`)

**Status:** written, tested on the SDK emulator, switched off.

**Why:** the capability is not reachable on shipping LightOS. A developer building an
EPUB reader hit exactly this and reported it in
[Tool Library submissions now open!](https://github.com/orgs/lightphone/discussions/266):

> It works on the SDK emulator, but phones on LightOS 582 can't reach it yet.

Light's maintainer replied that the LightOS release carrying Tool Manager support for
external apps is "pretty darn close to release", and that submitting without it is fine.
Declaring the capability now would ship a feature that silently does nothing on a phone:
the tool would advertise a file browser the device cannot open.

**What is already written:**

- `ToolEntryPoint.getToolManagerManifest()` returns a `ClientToolManifest` with a
  `FileBrowserSpec` pointing at `documents`.
- `ToolEntryPoint.onToolManagerDataUpdate()` bumps a flow the library screen can watch.
- `DocumentRepository` already reads from `files/shared/documents`, which is the same
  directory the Tool Manager writes into. It does not know or care how a file arrived.

**To turn it on**, both of these, together:

1. `Features.TOOL_MANAGER_UPLOAD = true` in `tool/src/main/kotlin/com/benfridge/lightmark/Features.kt`
2. Uncomment the `capabilities` line in `tool/lighttool.toml`:
   ```toml
   capabilities = ["tool-manager-provider"]
   ```

Then verify on a real phone, not just the emulator — that is the whole point of the
holdback. The tool should appear by name in the device's Tool Manager with a
**Documents** folder that accepts uploads.

**Until then** documents arrive over `adb`, as described in the README, and the
bundled document in `assets/documents/` means the tool is not empty on first run.

## Highlight export as a Tool Manager download

**Status:** the export itself is built and works. Only the *delivery* is held back.

`HighlightExporter` writes every highlight to one Markdown file at
`files/shared/highlights/lightmark-highlights.md`. Today you fetch it with `adb`:

```bash
adb shell run-as com.benfridge.lightmark cat \
  files/shared/highlights/lightmark-highlights.md > highlights.md
```

The directory is named for the Tool Manager job key on purpose, following the SDK's own
`tool-manager-demo`. When `tool-manager-provider` works on a shipping LightOS, this
becomes a button in the Tool Manager's web UI that downloads the file through the
browser — and `HighlightExporter` does not change. What gets added is:

1. A job handler, which just calls the exporter:
   ```kotlin
   @LightJob(HighlightExporter.JOB_KEY)
   val exportJob: LightJobHandler = { ctx, _ ->
       val result = HighlightExporter(ctx.fileShare, dao).export()
       LightJobResult.Success(
           outputFilePath = HighlightExporter.FILE_NAME,
           message = "Highlights exported.",
       )
   }
   ```
2. A `JobSpec(.., HighlightExporter.JOB_KEY, ..)` added to the `roots` in
   `ToolEntryPoint.getToolManagerManifest()`, beside the existing `FileBrowserSpec`.

Both are gated on the same LightOS release as document upload, so they land together.

### Why there is no forward-to-email

The Light Phone forwards notes and photos to email, but that is LightOS internal code and
nothing is exposed to tools. Three separate things block a tool from doing it:

- **No service method.** The full set of `LightServiceMethod`s is `GetToken`,
  `GetVersion`, `SetRingtone`, `GetKeyboardOptions`, `GetUserPreferences`,
  `GetPermission`, `RequestPermissionComponent`, `DeviceKeyEvent`, `OpenDialer`,
  `GetCurrentLocation`, `GetDefaultLocation`, `RequestLocationUpdates`,
  `ReleaseLocationUpdates`. `OpenDialer` is the only hand-off to a LightOS app.
- **The generic Android route is rejected at build time.** `LightSdkPlugin` fails the
  build on `startActivity()`, `startService()`, `getSystemService()`, `contentResolver`,
  `LocalContext`, `LocalView`, and casts to `Activity` or `Context`. So no `ACTION_SEND`,
  no `mailto:`, and no back door through a context.
- **`LightIcons.COMPOSE_MESSAGE` exists, the mechanism does not.** Its only use in the SDK
  is `LightBottomBar`'s own preview, wired to `onClick = {}`.

Nobody upstream has asked for one either, so it is not a matter of waiting for a release.

A tool *could* POST highlights to a third-party API — `INTERNET` is allow-listed and
Ktor/OkHttp are already on `:sdk:client`'s classpath — but that argues against two of the
Tool Library criteria (disclose data leaving the device; favour local interaction over
internet connectivity), and it breaks when someone else changes an API. The file export
is the local-first answer, and the Tool Manager download is Light's own sanctioned way of
getting a file off the phone.

### Personal builds, and what replaces them

`tool/src/debug` does exactly that POST, to a private repo the author owns, so highlights
reach Notion without a cable. It is compiled into debug builds only, so the arguments
above still hold for what Light signs.

SDK v0.2.3 adds the sanctioned version: community tools can opt in to
[backups](https://github.com/lightphone/light-sdk/blob/v0.2.3/docs/backups/README.md) to
the user's own Google Drive, Dropbox or WebDAV. It depends on `tool-manager-provider`, so
it waits on the same LightOS release. When that ships, flag the highlights directory
`canBeBackedUp = true` and the debug sync can go.

## Local notifications

Not used, and not planned for v1. Light's team said in the same thread that notification
support "will be in the next LightOS release / next light-sdk release". A reader has no
reason to notify anyone, so this is noted only so the gap is not rediscovered later.

## Why pin the SDK to a tag

Light's build server resolves the SDK from release tags, not `main`. A developer building
against `main` reported builds failing on APIs the builder insisted did not exist, because
the tag lagged two weeks behind. `light-sdk` is therefore a submodule pinned at **v0.2.2**,
and `gradle.properties` carries the matching `sdkVersion=0.2.2`. Those two must move
together, and only to a tag that exists.

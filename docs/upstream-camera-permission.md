# Draft issue for lightphone/light-sdk

Not filed. Light asks that communication in its repositories comes from a human, so
read this over and post it yourself if you agree with it. Everything in it was checked
against the v0.2.2 submodule in this repo.

---

**Title:** `CAMERA` is declared for every tool in `:sdk:ui`'s manifest, and a tool
cannot opt out

---

Building a reader against v0.2.2, I noticed the APK asks for `android.permission.CAMERA`
even though the tool never opens a camera. It comes from the UI module every tool
depends on:

```xml
<!-- sdk/ui/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

`VIBRATE` reads as reasonable there — every tool can reach the vibrator through
`SealedLightActivity`. `CAMERA` is different: the only thing in `:sdk:ui` that uses it is
`LightCodeScanner`.

## Why a tool can't fix this itself

I tried, and I think all the doors are closed by design:

- A tool manifest, so `tools:node="remove"` has somewhere to live — the plugin rejects
  it outright: *"src/main/AndroidManifest.xml is not allowed — declare your tool's
  metadata and permissions in lighttool.toml"*.
- `ManifestGenerator` only ever **adds** `<uses-permission>` entries, from
  `lighttool.toml` plus `CAPABILITY_IMPLIED_PERMISSIONS`. There is no subtractive path.
- `resolutionStrategy` and `dependencySubstitution` are both blocked in build scripts.

Excluding the dependencies does work, and is worth doing on its own:

```kotlin
implementation(project(":sdk:client")) {
    exclude(group = "com.google.mlkit")
    exclude(group = "androidx.camera")
}
```

That took my release APK from **25.11 MB to 4.19 MB** — `libbarhopper_v3.so` is about
20 MB across four ABIs, plus ~860 KB of `.tflite` models. But it removes only the code
and the native libraries. The permission is declared in the manifest, so it survives,
and the tool still ships asking for camera access.

## Why it matters

Beyond the APK, it is a Tool Library review problem. The guidelines ask that tools
respect privacy and disclose what leaves the device, and a reading tool requesting
`CAMERA` is exactly the kind of thing a reviewer should stop on. Right now every tool
requests it, so the signal is worth nothing — and an author who wants to be careful
has no way to be.

## Suggested fix

The SDK already has both halves of this, and the scanner looks like it simply predates
them:

```kotlin
CAPABILITY_IMPLIED_PERMISSIONS = mapOf(
    DETACHED_AUDIO to listOf("android.permission.FOREGROUND_SERVICE", ...),
)
CAPABILITY_IMPLIED_DEPENDENCIES = mapOf(
    AUDIO_DASH to listOf("androidx.media3:media3-exoplayer-dash:$MEDIA3_VERSION"),
)
```

A `barcode-scanner` capability could use both: contribute `CAMERA` and the
`uses-feature` when declared, and put ML Kit and CameraX on the classpath only then.
`:sdk:ui`'s manifest drops both lines, tools that scan add one line to `lighttool.toml`,
and tools that don't lose a permission they never wanted and ~21 MB they never used.

The comment already in `CAPABILITY_IMPLIED_PERMISSIONS` makes the case better than I can:

> a bare platform permission says nothing about what the tool asked for (any transitive
> dependency can declare one), so the capability owns them instead

That is precisely the situation with `CAMERA` today.

The mechanical part is that `LightCodeScanner` needs ML Kit to compile, so the classes
probably have to move into their own module that the capability pulls in, rather than
the dependency simply moving. I'd be glad to put up a PR if the shape sounds right.

## Smaller thing in passing

`abiFilters += "arm64-v8a"` saved another ~0.1 MB after the exclusion, and much more
before it. It might be worth a line in the docs that tools can narrow to the device's
ABI, since nothing in the template hints at it.

Version: light-sdk v0.2.2 (92b8b86), AGP 8.12.3, built with `:tool:assembleRelease`.

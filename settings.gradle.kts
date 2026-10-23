pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // The SDK pulls the LP3 keyboard from JitPack.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("light-sdk/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "lightmark"

// light-sdk is a pristine submodule pinned to a release tag. Lightmark is the `tool/`
// module built against it -- the shape Light's server builds and signs tools from.
val sdkDir = file("light-sdk")
if (!File(sdkDir, "sdk").exists()) {
    error("The light-sdk submodule is empty. Run: git submodule update --init --recursive")
}

includeBuild("light-sdk/plugin")

include(":lint-rules")
project(":lint-rules").projectDir = file("light-sdk/lint-rules")

include(":sdk:shared")
project(":sdk").projectDir = file("light-sdk/sdk")
project(":sdk:shared").projectDir = file("light-sdk/sdk/shared")

include(":sdk:ui")
project(":sdk:ui").projectDir = file("light-sdk/sdk/ui")

include(":sdk:client")
project(":sdk:client").projectDir = file("light-sdk/sdk/client")

// Light's tool builder passes -DlightSdk.toolOnly=true and configures only what :tool
// needs. Keep the emulator-side modules out of that path so our build matches theirs.
if (providers.systemProperty("lightSdk.toolOnly").orNull != "true") {
    include(":sdk:trust")
    project(":sdk:trust").projectDir = file("light-sdk/sdk/trust")

    include(":sdk:server")
    project(":sdk:server").projectDir = file("light-sdk/sdk/server")

    include(":sdk:emulator")
    project(":sdk:emulator").projectDir = file("light-sdk/sdk/emulator")
}

include(":tool")

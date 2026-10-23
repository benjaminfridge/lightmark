package com.benfridge.lightmark

/**
 * Flags for platform features that exist in the SDK but not yet on shipping LightOS.
 *
 * See docs/HOLDBACK.md for why these are off and what flipping one involves.
 */
object Features {

    /**
     * Receive documents through the Tool Manager, over local WiFi.
     *
     * Off because `tool-manager-provider` is not reachable on LightOS 582 — the tool
     * appears in the emulator's Tool Manager but not on a phone. Turning it on means
     * this flag *and* uncommenting the `capabilities` line in `lighttool.toml`; the
     * manifest code it guards is already written.
     *
     * Until then, documents arrive by:
     *   adb push my-notes.md /sdcard/ && adb shell run-as com.benfridge.lightmark \
     *     cp /sdcard/my-notes.md files/shared/documents/
     */
    const val TOOL_MANAGER_UPLOAD = false
}

package org.tegro.yamp.presentation.sources

/**
 * Whether the app's own folder chooser has to hand the user to the system picker (D93, 2026-11-22): the file API can't
 * read the volume at all, or it can't list the level being shown — a mount point that passes the access probe but
 * refuses a listing (the head unit's USB stick). "Use this folder" is off in both cases.
 *
 * Pure, so the condition is unit-tested without a device.
 */
internal fun chooserNeedsSystemPicker(readable: Boolean, levelFailed: Boolean): Boolean = !readable || levelFailed

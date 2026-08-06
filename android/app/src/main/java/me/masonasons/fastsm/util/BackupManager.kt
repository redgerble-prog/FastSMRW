package me.masonasons.fastsm.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backs up and restores everything the native core has written to disk —
 * accounts (including sign-in tokens), settings, timeline configuration, and
 * custom keymaps — as a plain zip file the user picks a location for via
 * Android's document picker. This works entirely at the file level: the core
 * treats `config_dir`/`keymaps_dir` (see [me.masonasons.fastsm.core.FastSmCore])
 * as an opaque folder it owns, so there's no need for the native core itself
 * to know anything about backup/restore — we just copy what's already there.
 *
 * Restore replaces the folders wholesale and requires a full process restart
 * (see MainActivity) so the core initializes fresh from the restored files,
 * rather than trying to reconcile them with whatever it already has in memory.
 */
object BackupManager {
    private const val CORE_DIR = "core"
    private const val KEYMAPS_DIR = "keymaps"

    /** Zips config_dir + keymaps_dir into the document the user picked. */
    fun backup(context: Context, destination: Uri): Result<Unit> = runCatching {
        val resolver = context.contentResolver
        resolver.openOutputStream(destination)?.use { out ->
            ZipOutputStream(out).use { zip ->
                addDirToZip(zip, File(context.filesDir, CORE_DIR), CORE_DIR)
                addDirToZip(zip, File(context.filesDir, KEYMAPS_DIR), KEYMAPS_DIR)
            }
        } ?: error("Couldn't open the chosen file for writing")
    }

    /**
     * Replaces config_dir + keymaps_dir with what's in the picked zip. Only
     * succeeds if the zip actually looks like a FastSMRW backup (contains a
     * top-level "core/" entry) — otherwise leaves existing data untouched.
     */
    fun restore(context: Context, source: Uri): Result<Unit> = runCatching {
        val resolver = context.contentResolver
        val tempDir = File(context.cacheDir, "restore-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            resolver.openInputStream(source)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var sawCoreEntry = false
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name.startsWith("$CORE_DIR/") || name.startsWith("$KEYMAPS_DIR/")) {
                            if (name.startsWith("$CORE_DIR/")) sawCoreEntry = true
                            val outFile = File(tempDir, name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out -> zip.copyTo(out) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    if (!sawCoreEntry) error("That file doesn't look like a FastSMRW backup")
                }
            } ?: error("Couldn't open the chosen file for reading")

            // Only swap in the restored folders once the whole zip has extracted
            // successfully, so a bad/partial file can't half-clobber real data.
            val restoredCore = File(tempDir, CORE_DIR)
            val restoredKeymaps = File(tempDir, KEYMAPS_DIR)
            val liveCore = File(context.filesDir, CORE_DIR)
            val liveKeymaps = File(context.filesDir, KEYMAPS_DIR)
            if (restoredCore.exists()) {
                liveCore.deleteRecursively()
                restoredCore.copyRecursively(liveCore, overwrite = true)
            }
            if (restoredKeymaps.exists()) {
                liveKeymaps.deleteRecursively()
                restoredKeymaps.copyRecursively(liveKeymaps, overwrite = true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun addDirToZip(zip: ZipOutputStream, dir: File, entryPrefix: String) {
        if (!dir.exists()) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            val entryName = "$entryPrefix/${f.name}"
            if (f.isDirectory) {
                addDirToZip(zip, f, entryName)
            } else {
                zip.putNextEntry(ZipEntry(entryName))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
  

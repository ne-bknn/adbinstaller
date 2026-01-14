package com.ne_bknn.adbinstaller.apk

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

data class ApkSource(
    val uri: Uri,
    val stagedFile: File,
    val displayName: String,
    val sizeBytes: Long,
) {
    fun cleanup() {
        stagedFile.delete()
    }

    companion object {
        fun fromUri(context: Context, uri: Uri): ApkSource {
            val meta = queryMeta(context, uri)
            val displayName = meta.displayName ?: "selected.apk"
            val safeName = displayName.replace(Regex("""[^\w\-.]+"""), "_")
            val outFile = File(context.cacheDir, "staged_${System.currentTimeMillis()}_$safeName")

            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open APK stream" }
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }

            val size = outFile.length()
            return ApkSource(
                uri = uri,
                stagedFile = outFile,
                displayName = displayName,
                sizeBytes = if (size > 0) size else (meta.sizeBytes ?: 0L),
            )
        }

        private data class Meta(val displayName: String?, val sizeBytes: Long?)

        private fun queryMeta(context: Context, uri: Uri): Meta {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
            cursor.use {
                if (it == null || !it.moveToFirst()) return Meta(null, null)
                val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) it.getString(nameIdx) else null
                val size = if (sizeIdx >= 0) it.getLong(sizeIdx) else null
                return Meta(name, size)
            }
        }
    }
}


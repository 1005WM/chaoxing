// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.state

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.WebView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试用：把当前网页保存为 MHTML（含源码），写入「下载/DSH-Go」，便于排查页面结构。
 * 只读本地导出，不上传任何服务器。
 */
object AssessmentPageExporter {
    fun export(
        context: Context,
        webView: WebView,
        extraText: String,
        onDone: (String?) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onDone(null)
            return
        }
        val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
        val cacheFile = File(context.cacheDir, "page-$stamp.mhtml")
        val textSaved = runCatching {
            val uri = insert(context, "webpage-$stamp.txt", "text/plain") ?: return@runCatching null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(extraText.toByteArray(Charsets.UTF_8))
            }
            "下载/DSH-Go/webpage-$stamp.txt"
        }.getOrNull()
        try {
            webView.saveWebArchive(cacheFile.absolutePath, false) { path ->
                val file = File(path ?: cacheFile.absolutePath)
                Thread {
                    val saved = runCatching {
                        val uri = insert(context, "webpage-$stamp.mhtml", "multipart/related")
                            ?: return@runCatching null
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(file.readBytes())
                        } ?: return@runCatching null
                        "下载/DSH-Go/webpage-$stamp.mhtml"
                    }.getOrNull()
                    file.delete()
                    Handler(Looper.getMainLooper()).post { onDone(saved ?: textSaved) }
                }.start()
            }
        } catch (e: Exception) {
            onDone(null)
        }
    }

    private fun insert(context: Context, name: String, mime: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/DSH-Go",
            )
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }
}

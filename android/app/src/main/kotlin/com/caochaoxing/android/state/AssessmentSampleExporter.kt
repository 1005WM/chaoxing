// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.state

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把课程页面样本写入「下载/DSH-Go」目录，供用户自行检查或反馈给维护者修正解析。
 *
 * 只在用户点按钮时执行；内容是该次请求返回的 HTML 片段（可能含作业标题、姓名等），
 * 不会自动上传到任何服务器。
 */
object AssessmentSampleExporter {
    fun export(context: Context, text: String): String? {
        if (text.isBlank()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
            val fileName = "assessment-sample-$stamp.txt"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/DSH-Go",
                )
            }
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(text.toByteArray(Charsets.UTF_8))
                } ?: run {
                    resolver.delete(uri, null, null)
                    return null
                }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            "下载/DSH-Go/$fileName"
        }.getOrNull()
    }
}

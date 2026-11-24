// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.state

import android.content.Context
import java.security.MessageDigest

/** 仅保存账号对应的本地完成标记，不保存题目链接或登录信息。 */
internal class ManualAssessmentCompletionStore(context: Context) {
    private val preferences = context.getSharedPreferences("assessment-completions", Context.MODE_PRIVATE)

    fun load(phoneNumber: String): Set<String> =
        preferences.getStringSet(accountKey(phoneNumber), emptySet()).orEmpty().toSet()

    fun save(phoneNumber: String, keys: Set<String>) {
        preferences.edit().putStringSet(accountKey(phoneNumber), keys.toSet()).apply()
    }

    private fun accountKey(phoneNumber: String): String = MessageDigest.getInstance("SHA-256")
        .digest(phoneNumber.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import com.caochaoxing.android.ui.screens.isAllowedAssessmentUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentWebUrlTest {
    @Test
    fun onlyHttpsChaoxingHostsAreAllowed() {
        assertTrue(isAllowedAssessmentUrl("https://mooc1-api.chaoxing.com/work/phone/task-work?workId=1"))
        assertTrue(isAllowedAssessmentUrl("https://mooc1.chaoxing.com/mooc-ans/work/index"))
        assertTrue(isAllowedAssessmentUrl("https://chaoxing.com/"))
    }

    @Test
    fun lookalikeOrInsecureHostsAreRejected() {
        assertFalse(isAllowedAssessmentUrl("http://mooc1.chaoxing.com/work"))
        assertFalse(isAllowedAssessmentUrl("https://evilchaoxing.com/work"))
        assertFalse(isAllowedAssessmentUrl("https://chaoxing.com.evil.com/work"))
        assertFalse(isAllowedAssessmentUrl("https://example.com/"))
        assertFalse(isAllowedAssessmentUrl(""))
    }
}

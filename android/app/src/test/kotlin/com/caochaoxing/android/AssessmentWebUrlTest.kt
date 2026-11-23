// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import com.caochaoxing.android.ui.screens.desktopAssessmentUrl
import com.caochaoxing.android.ui.screens.isAllowedAssessmentUrl
import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun workItemsMapToDesktopHomeworkPage() {
        val item = ChaoxingAssessmentItem(
            kind = ChaoxingAssessmentKind.WORK,
            title = "结课作业",
            statusText = "做作业",
            courseId = 300001L,
            classId = 200001,
            taskUrl = "https://mooc1-api.chaoxing.com/work/phone/task-work?courseId=300001&classId=200001&workId=9001",
        )
        assertEquals(
            "https://mooc1.chaoxing.com/mooc-ans/work/doHomeWorkNew?courseId=300001&classId=200001&workId=9001",
            desktopAssessmentUrl(item),
        )
    }

    @Test
    fun examsAndIncompleteLinksStayWithoutDesktopMapping() {
        val exam = ChaoxingAssessmentItem(
            kind = ChaoxingAssessmentKind.EXAM,
            title = "期末考试",
            statusText = "未开始",
            courseId = 300001L,
            classId = 200001,
            taskUrl = "https://mooc1-api.chaoxing.com/exam/phone/task-exam?examId=8001",
        )
        assertNull(desktopAssessmentUrl(exam))

        val noId = ChaoxingAssessmentItem(
            kind = ChaoxingAssessmentKind.WORK,
            title = "作业",
            statusText = "做作业",
            taskUrl = "https://mooc1-api.chaoxing.com/work/phone/task-work",
        )
        assertNull(desktopAssessmentUrl(noId))
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

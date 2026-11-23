// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import com.caochaoxing.android.ui.screens.desktopAssessmentCourseFallback
import com.caochaoxing.android.ui.screens.desktopAssessmentUrl
import com.caochaoxing.android.ui.screens.isAllowedAssessmentUrl
import com.caochaoxing.android.ui.screens.isDesktopAssessmentUrl
import com.caochaoxing.android.ui.screens.isMobileHomeworkUrl
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
    fun mappingSurvivesChineseTitleAndUppercaseKeys() {
        val item = ChaoxingAssessmentItem(
            kind = ChaoxingAssessmentKind.WORK,
            title = "任务5-2 分组查询",
            statusText = "做作业",
            courseId = 300001L,
            classId = 200001,
            taskUrl = "https://mooc1-api.chaoxing.com/work/phone/task-work" +
                "?CourseId=300001&ClassId=200001&WorkId=9001&title=任务 5-2 分组查询",
        )
        assertEquals(
            "https://mooc1.chaoxing.com/mooc-ans/work/doHomeWorkNew?courseId=300001&classId=200001&workId=9001",
            desktopAssessmentUrl(item),
        )
    }

    @Test
    fun missingWorkIdOpensDesktopCourse() {
        val item = ChaoxingAssessmentItem(
            kind = ChaoxingAssessmentKind.WORK,
            title = "作业",
            statusText = "做作业",
            courseId = 300001L,
            classId = 200001,
            taskUrl = "https://mooc1-api.chaoxing.com/work/phone/task-work?courseId=300001&classId=200001",
        )
        assertEquals(
            "https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=300001&clazzid=200001&cpi=7001&vc=1&ismooc2=1&v=2",
            desktopAssessmentUrl(item, courseCpi = 7001),
        )
    }

    @Test
    fun desktopLinksKeepServerParameters() {
        val item = ChaoxingAssessmentItem(
            kind = ChaoxingAssessmentKind.WORK,
            title = "作业",
            statusText = "做作业",
            taskUrl = "http://mooc1-2.chaoxing.com/mooc-ans/mooc2/work/task" +
                "?courseId=3&classId=2&cpi=7&workId=9&answerId=8&enc=a%2Bb&other=1",
        )

        val desktop = "https://mooc1-2.chaoxing.com/mooc-ans/mooc2/work/task" +
            "?courseId=3&classId=2&cpi=7&workId=9&answerId=8&enc=a%2Bb&other=1"
        assertEquals(desktop, desktopAssessmentUrl(item))
        assertTrue(isDesktopAssessmentUrl(desktop))
        val course = "https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=3&clazzid=2&cpi=7&vc=1&ismooc2=1&v=2"
        assertEquals(course, desktopAssessmentCourseFallback(desktop))
        assertEquals(course, desktopAssessmentCourseFallback(
            "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/list?courseId=3&classId=2&cpi=7",
        ))
        assertNull(desktopAssessmentCourseFallback(course))
        assertTrue(isDesktopAssessmentUrl(course))
    }

    @Test
    fun mobileLinksCarryCourseAndAnswerContextToDesktop() {
        val work = ChaoxingAssessmentItem(
            kind = ChaoxingAssessmentKind.WORK,
            title = "作业",
            statusText = "做作业",
            taskUrl = "https://mooc1-api.chaoxing.com/work/phone/task-work" +
                "?courseId=3&clazzId=2&cpi=7&workId=9&workAnswerId=8&enc=a%2Bb&ut=s",
        )
        val base = "https://mooc1.chaoxing.com/mooc-ans"
        assertEquals(
            "$base/mooc2/work/task?courseId=3&classId=2&cpi=7&workId=9&answerId=8&enc=a%2Bb&ut=s",
            desktopAssessmentUrl(work, courseCpi = 999),
        )
        assertEquals(
            "$base/work/doHomeWorkNew?courseId=3&classId=2&cpi=7&workId=9",
            desktopAssessmentUrl(work.copy(taskUrl = work.taskUrl?.substringBefore("&workAnswerId"))),
        )
    }

    @Test
    fun detectsMobileHomeworkNavigationWithoutMatchingDesktopOrExams() {
        val base = "https://mooc1.chaoxing.com"
        assertTrue(isMobileHomeworkUrl("$base/mooc-ans/work/phone/doHomeWork?workId=1"))
        assertTrue(isMobileHomeworkUrl("$base/phone/moocAnalysis/workList?courseId=1"))
        assertFalse(isMobileHomeworkUrl("$base/mooc-ans/mooc2/work/task?workId=1"))
        assertFalse(isMobileHomeworkUrl("$base/exam/phone/task-exam?examId=1"))
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

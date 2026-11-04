package app.banzhan.deepseekcontinuation.ui

import org.junit.Assert.*
import org.junit.Test

class OrganizationFailureReasonTest {
    @Test fun localPreflightRejectionKeepsTheNoModelCallStatement() {
        val message = "请求结构或旧提示快照已不兼容，本次未调用模型，请重新提交。"
        assertEquals(message, organizationFailureSummary("[content_shape] $message diagnostic={\"http_status\":null}"))
    }

    @Test fun accessDeniedRequiresConfigurationReviewInsteadOfRepeatedRetry() {
        listOf(401, 403).forEach { status ->
            val summary = organizationFailureSummary("[transport] 上游请求失败（HTTP $status）。 diagnostic={\"http_status\":$status}")
            assertTrue(summary.contains("HTTP $status"))
            assertTrue(summary.contains("访问权限"))
            assertFalse(summary.contains("稍后继续"))
        }
    }

    @Test fun badGatewayHasReadableCauseWithoutDiagnosticJson() {
        val summary = organizationFailureSummary("[transport] 服务方未返回可核实结果。 diagnostic={\"http_status\":502,\"response_bytes\":0}")
        assertTrue(summary.contains("HTTP 502"))
        assertTrue(summary.contains("已完成部分"))
        assertFalse(summary.contains("diagnostic"))
        assertFalse(summary.contains('{'))
    }

    @Test fun providerJsonAndSummaryJsonFailuresRemainDistinct() {
        assertTrue(organizationFailureSummary("[envelope_json] invalid diagnostic={\"http_status\":200}").contains("无法读取的响应"))
        assertTrue(organizationFailureSummary("[json][usage_unknown] invalid diagnostic={}").contains("完整的整理结果"))
    }

    @Test fun timeoutAndOutputLimitDoNotBlameTheDailyQuota() {
        assertTrue(organizationFailureSummary("请求达到总时限，本次次数已计入用量，不会自动重试").contains("超时"))
        val output = organizationFailureSummary("[output_limit] 模型回复因长度限制未完整结束 diagnostic={}")
        assertTrue(output.contains("输出上限"))
        assertFalse(output.contains("每日"))
    }

    @Test fun sourceValidationRemainsClearAndPlainQuotaMessagesArePreserved() {
        assertTrue(organizationFailureSummary("[unknown_source] invalid diagnostic={}").contains("来源检查"))
        val quota = "你今日的免费整理次数已用完，请明天再试"
        assertEquals(quota, organizationFailureSummary(quota))
    }

    @Test fun unrecognizedOrOversizeMachineMessagesHaveABoundedSummary() {
        val summary = organizationFailureSummary("unrecognized {\"field\":\"${"x".repeat(2000)}\"}")
        assertTrue(summary.length < 100)
        assertFalse(summary.contains("field"))
    }
}

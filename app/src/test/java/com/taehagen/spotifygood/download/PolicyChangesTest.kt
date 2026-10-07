package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.download.DownloadRules.PolicyChange
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyChangesTest {
    @Test
    fun theLoadedSettingsAreTheReferenceNotAChange() = runTest {
        // Cold start with mobile data downloads on: the first stored value must not stop the job.
        val changes = DownloadRules.policyChanges(flowOf(true to false, true to false)).toList()
        assertEquals(emptyList<PolicyChange>(), changes)
    }

    @Test
    fun aRealToggleIsReportedOnce() = runTest {
        val changes = DownloadRules.policyChanges(
            flowOf(true to false, false to false, false to true, false to false, true to false),
        ).toList()
        assertEquals(
            listOf(
                PolicyChange(offline = false, cellularChanged = true),
                PolicyChange(offline = true, cellularChanged = false),
                PolicyChange(offline = false, cellularChanged = false),
                PolicyChange(offline = false, cellularChanged = true),
            ),
            changes,
        )
    }

    @Test
    fun aPlaceholderFirstValueWouldLookLikeAToggle() = runTest {
        // Why the manager feeds the stored settings (SettingsRepository.persisted), not the StateFlow
        // that starts with the defaults (mobile data off) before DataStore has loaded.
        val changes = DownloadRules.policyChanges(flowOf(false to false, true to false)).toList()
        assertEquals(listOf(PolicyChange(offline = false, cellularChanged = true)), changes)
    }
}

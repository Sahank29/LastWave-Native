package com.lastwave.app.data.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUrlValidationPolicyTest {
    private var now = 1_000L
    private val policy = PlaybackUrlValidationPolicy { now }

    @Test fun firstLoadUsesThePlayerMediaRequest() {
        assertFalse(policy.requiresProbe("video", "account"))
    }

    @Test fun rejectedFirstUrlGetsOneValidatedRetryBeforeClientCooldown() {
        policy.recordUnverified("video", "account", "WEB_REMIX")
        assertTrue(policy.retryWithProbe("video", "account", "WEB_REMIX"))
        assertTrue(policy.requiresProbe("video", "account"))
        assertFalse(policy.retryWithProbe("video", "account", "WEB_REMIX"))
    }

    @Test fun validatedRetryCannotBeMarkedAsAnotherFirstAttempt() {
        policy.recordUnverified("video", "account", "WEB_REMIX")
        assertTrue(policy.retryWithProbe("video", "account", "WEB_REMIX"))
        policy.recordUnverified("video", "account", "WEB_REMIX")
        assertFalse(policy.retryWithProbe("video", "account", "WEB_REMIX"))
    }

    @Test fun failuresAreIsolatedByTrackAccountAndClient() {
        policy.recordUnverified("video", "account", "WEB_REMIX")
        assertFalse(policy.retryWithProbe("video", "other-account", "WEB_REMIX"))
        assertFalse(policy.retryWithProbe("video", "account", "IOS"))
        assertTrue(policy.retryWithProbe("video", "account", "WEB_REMIX"))
        assertFalse(policy.requiresProbe("other-video", "account"))
        assertFalse(policy.requiresProbe("video", "other-account"))
    }

    @Test fun expiredUnverifiedUrlsUseNormalFailureHandling() {
        policy.recordUnverified("video", "account", "WEB_REMIX")
        now += 60_001L
        assertFalse(policy.retryWithProbe("video", "account", "WEB_REMIX"))
    }

    @Test fun probeRequirementExpires() {
        policy.recordUnverified("video", "account", "WEB_REMIX")
        policy.retryWithProbe("video", "account", "WEB_REMIX")
        now += 60_001L
        assertFalse(policy.requiresProbe("video", "account"))
    }
}

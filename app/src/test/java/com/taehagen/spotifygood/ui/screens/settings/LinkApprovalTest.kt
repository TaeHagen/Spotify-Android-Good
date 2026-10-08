package com.taehagen.spotifygood.ui.screens.settings

import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkApprovalTest {
    private val hosts = SpotifyLinkApproval.HOSTS

    @Test
    fun aFreshInstallMustApproveTheLinks() {
        // Unverifiable domain, nothing chosen by the user: the browser gets the links.
        assertTrue(linksNeedApproval(true, mapOf("open.spotify.com" to DOMAIN_STATE_NONE), hosts))
    }

    @Test
    fun approvedLinksHideTheRow() {
        assertFalse(linksNeedApproval(true, mapOf("open.spotify.com" to DOMAIN_STATE_SELECTED), hosts))
        assertFalse(linksNeedApproval(true, mapOf("open.spotify.com" to DOMAIN_STATE_VERIFIED), hosts))
    }

    @Test
    fun linkHandlingTurnedOffNeedsItAgain() {
        // "Open supported links" off for the app: even a selected domain opens in the browser.
        assertTrue(linksNeedApproval(false, mapOf("open.spotify.com" to DOMAIN_STATE_SELECTED), hosts))
    }

    @Test
    fun aHostTheSystemDoesNotListIsNotAsked() {
        assertFalse(linksNeedApproval(true, emptyMap(), hosts))
    }
}

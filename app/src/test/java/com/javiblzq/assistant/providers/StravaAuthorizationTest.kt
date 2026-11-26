package com.javiblzq.assistant.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StravaAuthorizationTest {
    @Test
    fun readsTheCodeAndGrantedScopesFromLocalhostReturn() {
        val url = "http://localhost:8080/api/strava/callback?state=nonce&code=test-code&scope=read%2Cactivity%3Aread_all#fragment"
        assertEquals("test-code", StravaClient.authorizationCodeFrom(url))
        assertTrue(StravaClient.hasActivityScope(StravaClient.callbackParameter(url, "scope").orEmpty()))
    }

    @Test
    fun doesNotTreatOtherScopesAsActivityReadPermission() {
        assertFalse(StravaClient.hasActivityScope("read,profile:read_all,activity:write"))
        assertTrue(StravaClient.hasActivityScope("read activity:read"))
        assertFalse(StravaClient.hasActivityScope(""))
    }

    @Test
    fun preservesRawCodesAndRejectsMalformedReturnCodes() {
        val rawCode = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(rawCode, StravaClient.authorizationCodeFrom(rawCode))
        assertNull(StravaClient.authorizationCodeFrom("http://localhost/?code=%ZZ"))
        assertNull(StravaClient.authorizationCodeFrom("http://localhost/?error=access_denied#?code=invalid"))
    }
}

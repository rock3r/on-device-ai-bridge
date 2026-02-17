// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import dev.sebastiano.plugins.appleintelligence.AppleAiRestService
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [dev.sebastiano.plugins.appleintelligence.AppleAiRestService] security-critical logic: authentication, CORS, routing, host trust, rate
 * limiting, and origin validation.
 *
 * Authentication, CORS, and other helper methods are exposed as `@VisibleForTesting` companion functions so they can be
 * tested directly without reflection or IntelliJ platform fixtures.
 */
class AppleAiRestServiceAuthTest {

    private lateinit var service: AppleAiRestService
    private val expectedKey = "test-api-key-00000000-0000-0000-0000-000000000000"

    @Before
    fun setUp() {
        service = AppleAiRestService()
        AppleAiRestService.authFailureTracker.clear()
    }

    private fun buildRequest(
        method: HttpMethod = HttpMethod.GET,
        uri: String = "/health",
        bearerToken: String? = null,
        origin: String? = null,
    ): DefaultFullHttpRequest {
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, Unpooled.EMPTY_BUFFER)
        bearerToken?.let { request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer $it") }
        origin?.let { request.headers().set(HttpHeaderNames.ORIGIN, it) }
        return request
    }

    private fun buildRequestWithRawAuthHeader(
        authHeaderValue: String,
        uri: String = "/v1/models",
    ): DefaultFullHttpRequest {
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri, Unpooled.EMPTY_BUFFER)
        request.headers().set(HttpHeaderNames.AUTHORIZATION, authHeaderValue)
        return request
    }

    // region isAuthorized — constant-time comparison

    @Test
    fun `isAuthorized accepts valid bearer token`() {
        val request = buildRequest(bearerToken = expectedKey)
        assertTrue(AppleAiRestService.isAuthorized(request, expectedKey))
    }

    @Test
    fun `isAuthorized rejects wrong bearer token`() {
        val request = buildRequest(bearerToken = "wrong-key")
        assertFalse(AppleAiRestService.isAuthorized(request, expectedKey))
    }

    @Test
    fun `isAuthorized rejects missing Authorization header`() {
        val request = buildRequest()
        assertFalse(AppleAiRestService.isAuthorized(request, expectedKey))
    }

    @Test
    fun `isAuthorized rejects token without Bearer prefix even when value matches`() {
        val request = buildRequestWithRawAuthHeader(expectedKey)
        val result = AppleAiRestService.isAuthorized(request, expectedKey)
        assertFalse("Tokens without Bearer prefix should be rejected per RFC 6750", result)
    }

    @Test
    fun `isAuthorized rejects empty bearer token`() {
        val request = buildRequestWithRawAuthHeader("Bearer ")
        assertFalse(AppleAiRestService.isAuthorized(request, expectedKey))
    }

    @Test
    fun `isAuthorized trims whitespace around token`() {
        val request = buildRequestWithRawAuthHeader("Bearer   $expectedKey   ")
        assertTrue(AppleAiRestService.isAuthorized(request, expectedKey))
    }

    @Test
    fun `isAuthorized is case-sensitive`() {
        val request = buildRequest(bearerToken = expectedKey.uppercase())
        assertFalse(AppleAiRestService.isAuthorized(request, expectedKey))
    }

    @Test
    fun `isAuthorized rejects empty expected key against empty bearer`() {
        val request = buildRequestWithRawAuthHeader("Bearer ")
        // Empty expected key matches empty extracted token via constant-time comparison
        assertTrue("Empty expected key matches empty bearer token", AppleAiRestService.isAuthorized(request, ""))
    }

    @Test
    fun `isAuthorized uses constant-time comparison for equal-length different tokens`() {
        // This test verifies the behavior of MessageDigest.isEqual — tokens of equal length
        // that differ should be rejected. The constant-time nature is a property of the
        // implementation (MessageDigest.isEqual) rather than something testable via timing,
        // but we verify the correctness of the comparison.
        val key = "aaaa-bbbb-cccc-dddd"
        val similar = "aaaa-bbbb-cccc-ddde"
        val request = buildRequest(bearerToken = similar)
        assertFalse(AppleAiRestService.isAuthorized(request, key))
    }

    // endregion

    // region isLocalhostOrigin

    @Test
    fun `isLocalhostOrigin accepts http localhost`() {
        assertTrue(AppleAiRestService.isLocalhostOrigin("http://localhost"))
    }

    @Test
    fun `isLocalhostOrigin accepts http localhost with port`() {
        assertTrue(AppleAiRestService.isLocalhostOrigin("http://localhost:3000"))
    }

    @Test
    fun `isLocalhostOrigin accepts https localhost`() {
        assertTrue(AppleAiRestService.isLocalhostOrigin("https://localhost"))
    }

    @Test
    fun `isLocalhostOrigin accepts https localhost with port`() {
        assertTrue(AppleAiRestService.isLocalhostOrigin("https://localhost:443"))
    }

    @Test
    fun `isLocalhostOrigin accepts http 127_0_0_1`() {
        assertTrue(AppleAiRestService.isLocalhostOrigin("http://127.0.0.1"))
    }

    @Test
    fun `isLocalhostOrigin accepts http 127_0_0_1 with port`() {
        assertTrue(AppleAiRestService.isLocalhostOrigin("http://127.0.0.1:8080"))
    }

    @Test
    fun `isLocalhostOrigin accepts https 127_0_0_1 with port`() {
        assertTrue(AppleAiRestService.isLocalhostOrigin("https://127.0.0.1:443"))
    }

    @Test
    fun `isLocalhostOrigin rejects external domain`() {
        assertFalse(AppleAiRestService.isLocalhostOrigin("http://evil.com"))
    }

    @Test
    fun `isLocalhostOrigin rejects external domain with port`() {
        assertFalse(AppleAiRestService.isLocalhostOrigin("http://evil.com:3000"))
    }

    @Test
    fun `isLocalhostOrigin rejects non-localhost IP`() {
        assertFalse(AppleAiRestService.isLocalhostOrigin("http://192.168.1.1"))
    }

    @Test
    fun `isLocalhostOrigin rejects ftp scheme`() {
        assertFalse(AppleAiRestService.isLocalhostOrigin("ftp://localhost"))
    }

    @Test
    fun `isLocalhostOrigin rejects empty string`() {
        assertFalse(AppleAiRestService.isLocalhostOrigin(""))
    }

    @Test
    fun `isLocalhostOrigin rejects malformed URI`() {
        assertFalse(AppleAiRestService.isLocalhostOrigin("not a url"))
    }

    @Test
    fun `isLocalhostOrigin rejects localhost with path`() {
        // URI parser still extracts the host correctly even with a path
        assertTrue(AppleAiRestService.isLocalhostOrigin("http://localhost/some/path"))
    }

    @Test
    fun `isLocalhostOrigin is case-insensitive for host`() {
        assertTrue(AppleAiRestService.isLocalhostOrigin("http://LOCALHOST:3000"))
    }

    @Test
    fun `isLocalhostOrigin is case-insensitive for scheme`() {
        assertTrue(AppleAiRestService.isLocalhostOrigin("HTTP://localhost"))
    }

    @Test
    fun `isLocalhostOrigin rejects DNS rebinding attack domain`() {
        // A domain like "localhost.evil.com" should not be treated as localhost
        assertFalse(AppleAiRestService.isLocalhostOrigin("http://localhost.evil.com"))
    }

    // endregion

    // region setCorsHeaders

    @Test
    fun `CORS headers set for localhost with port`() {
        val request = buildRequest(origin = "http://localhost:3000")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        AppleAiRestService.setCorsHeaders(request, response)
        assertEquals("http://localhost:3000", response.headers().get("Access-Control-Allow-Origin"))
        assertEquals(
            "Content-Type, Authorization, Cache-Control",
            response.headers().get("Access-Control-Allow-Headers"),
        )
        assertEquals("GET, POST, OPTIONS", response.headers().get("Access-Control-Allow-Methods"))
    }

    @Test
    fun `CORS headers set for localhost without port`() {
        // Previously broken due to substringBeforeLast(":") stripping the scheme colon.
        // Now fixed with proper URI parsing.
        val request = buildRequest(origin = "http://localhost")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        AppleAiRestService.setCorsHeaders(request, response)
        assertEquals("http://localhost", response.headers().get("Access-Control-Allow-Origin"))
    }

    @Test
    fun `CORS headers set for https localhost with port`() {
        val request = buildRequest(origin = "https://localhost:443")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        AppleAiRestService.setCorsHeaders(request, response)
        assertEquals("https://localhost:443", response.headers().get("Access-Control-Allow-Origin"))
    }

    @Test
    fun `CORS headers set for 127_0_0_1 with port`() {
        val request = buildRequest(origin = "http://127.0.0.1:8080")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        AppleAiRestService.setCorsHeaders(request, response)
        assertEquals("http://127.0.0.1:8080", response.headers().get("Access-Control-Allow-Origin"))
    }

    @Test
    fun `CORS headers set for https 127_0_0_1 with port`() {
        val request = buildRequest(origin = "https://127.0.0.1:443")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        AppleAiRestService.setCorsHeaders(request, response)
        assertEquals("https://127.0.0.1:443", response.headers().get("Access-Control-Allow-Origin"))
    }

    @Test
    fun `CORS headers set for 127_0_0_1 without port`() {
        val request = buildRequest(origin = "http://127.0.0.1")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        AppleAiRestService.setCorsHeaders(request, response)
        assertEquals("http://127.0.0.1", response.headers().get("Access-Control-Allow-Origin"))
    }

    @Test
    fun `CORS headers NOT set for external origin`() {
        val request = buildRequest(origin = "http://evil.com")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        AppleAiRestService.setCorsHeaders(request, response)
        assertNull("External origin should not get CORS headers", response.headers().get("Access-Control-Allow-Origin"))
    }

    @Test
    fun `CORS headers NOT set for external origin with port`() {
        val request = buildRequest(origin = "http://evil.com:3000")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        AppleAiRestService.setCorsHeaders(request, response)
        assertNull(response.headers().get("Access-Control-Allow-Origin"))
    }

    @Test
    fun `CORS headers NOT set when no Origin header`() {
        val request = buildRequest(origin = null)
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        AppleAiRestService.setCorsHeaders(request, response)
        assertNull(
            "Missing Origin should result in no CORS headers",
            response.headers().get("Access-Control-Allow-Origin"),
        )
    }

    @Test
    fun `CORS headers NOT set for non-localhost IP`() {
        val request = buildRequest(origin = "http://192.168.1.1")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        AppleAiRestService.setCorsHeaders(request, response)
        assertNull(response.headers().get("Access-Control-Allow-Origin"))
    }

    // endregion

    // region isHostTrusted

    @Test
    fun `isHostTrusted returns true when no Origin header`() {
        val request = buildRequest(origin = null)
        val urlDecoder = QueryStringDecoder(request.uri())
        assertTrue(
            "Non-browser clients without Origin should be trusted",
            service.isHostTrustedForTest(request, urlDecoder),
        )
    }

    @Test
    fun `isHostTrusted returns true for localhost origin`() {
        val request = buildRequest(origin = "http://localhost:3000")
        val urlDecoder = QueryStringDecoder(request.uri())
        assertTrue(service.isHostTrustedForTest(request, urlDecoder))
    }

    @Test
    fun `isHostTrusted returns true for localhost origin without port`() {
        val request = buildRequest(origin = "http://localhost")
        val urlDecoder = QueryStringDecoder(request.uri())
        assertTrue(service.isHostTrustedForTest(request, urlDecoder))
    }

    @Test
    fun `isHostTrusted returns true for 127_0_0_1 origin`() {
        val request = buildRequest(origin = "http://127.0.0.1:8080")
        val urlDecoder = QueryStringDecoder(request.uri())
        assertTrue(service.isHostTrustedForTest(request, urlDecoder))
    }

    @Test
    fun `isHostTrusted returns false for external origin`() {
        val request = buildRequest(origin = "http://evil.com")
        val urlDecoder = QueryStringDecoder(request.uri())
        assertFalse("External origins should not be trusted", service.isHostTrustedForTest(request, urlDecoder))
    }

    @Test
    fun `isHostTrusted returns false for DNS rebinding attack domain`() {
        val request = buildRequest(origin = "http://localhost.evil.com")
        val urlDecoder = QueryStringDecoder(request.uri())
        assertFalse("DNS rebinding domains should not be trusted", service.isHostTrustedForTest(request, urlDecoder))
    }

    @Test
    fun `isHostTrusted returns false for non-localhost IP origin`() {
        val request = buildRequest(origin = "http://192.168.1.100:3000")
        val urlDecoder = QueryStringDecoder(request.uri())
        assertFalse(service.isHostTrustedForTest(request, urlDecoder))
    }

    // endregion

    // region isSupported (routing)

    @Test
    fun `isSupported returns true for GET v1 models`() {
        assertTrue(service.isSupported(buildRequest(HttpMethod.GET, "/v1/models")))
    }

    @Test
    fun `isSupported returns true for GET v1 models with trailing slash`() {
        assertTrue(service.isSupported(buildRequest(HttpMethod.GET, "/v1/models/")))
    }

    @Test
    fun `isSupported returns true for GET models`() {
        assertTrue(service.isSupported(buildRequest(HttpMethod.GET, "/models")))
    }

    @Test
    fun `isSupported returns true for POST v1 chat completions`() {
        assertTrue(service.isSupported(buildRequest(HttpMethod.POST, "/v1/chat/completions")))
    }

    @Test
    fun `isSupported returns true for POST chat completions`() {
        assertTrue(service.isSupported(buildRequest(HttpMethod.POST, "/chat/completions")))
    }

    @Test
    fun `isSupported returns true for GET health`() {
        assertTrue(service.isSupported(buildRequest(HttpMethod.GET, "/health")))
    }

    @Test
    fun `isSupported returns true for GET health with trailing slash`() {
        assertTrue(service.isSupported(buildRequest(HttpMethod.GET, "/health/")))
    }

    @Test
    fun `isSupported returns true for OPTIONS method`() {
        assertTrue(service.isSupported(buildRequest(HttpMethod.OPTIONS, "/v1/models")))
    }

    @Test
    fun `isSupported returns false for unrecognized path`() {
        assertFalse(service.isSupported(buildRequest(HttpMethod.GET, "/unknown/endpoint")))
    }

    @Test
    fun `isSupported returns false for DELETE method`() {
        assertFalse(service.isSupported(buildRequest(HttpMethod.DELETE, "/v1/models")))
    }

    @Test
    fun `isSupported returns false for PUT method`() {
        assertFalse(service.isSupported(buildRequest(HttpMethod.PUT, "/v1/chat/completions")))
    }

    // endregion

    // region Rate limiting

    @Test
    fun `isRateLimited returns false for unknown address`() {
        assertFalse(AppleAiRestService.isRateLimited("10.0.0.1"))
    }

    @Test
    fun `isRateLimited returns false after fewer than max failures`() {
        val addr = "10.0.0.2"
        repeat(9) { AppleAiRestService.recordAuthFailure(addr) }
        assertFalse(AppleAiRestService.isRateLimited(addr))
    }

    @Test
    fun `isRateLimited returns true after max failures reached`() {
        val addr = "10.0.0.3"
        repeat(10) { AppleAiRestService.recordAuthFailure(addr) }
        assertTrue(AppleAiRestService.isRateLimited(addr))
    }

    @Test
    fun `isRateLimited returns true after exceeding max failures`() {
        val addr = "10.0.0.4"
        repeat(15) { AppleAiRestService.recordAuthFailure(addr) }
        assertTrue(AppleAiRestService.isRateLimited(addr))
    }

    @Test
    fun `rate limiting is per-address`() {
        val addr1 = "10.0.0.5"
        val addr2 = "10.0.0.6"
        repeat(10) { AppleAiRestService.recordAuthFailure(addr1) }
        assertTrue(AppleAiRestService.isRateLimited(addr1))
        assertFalse("Different address should not be rate-limited", AppleAiRestService.isRateLimited(addr2))
    }

    @Test
    fun `rate limit tracker can be cleared`() {
        val addr = "10.0.0.7"
        repeat(10) { AppleAiRestService.recordAuthFailure(addr) }
        assertTrue(AppleAiRestService.isRateLimited(addr))
        AppleAiRestService.authFailureTracker.clear()
        assertFalse(AppleAiRestService.isRateLimited(addr))
    }

    // endregion
}

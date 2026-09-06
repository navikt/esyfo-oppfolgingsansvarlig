package no.nav.syfo.ereg.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import defaultMocks
import getMockEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.clearAllMocks
import io.mockk.mockk
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault
import organisasjon

class EregClientTest :
    DescribeSpec({
        val texasHttpClient = mockk<TexasHttpClient>(relaxed = true)
        beforeTest {
            clearAllMocks(currentThreadOnly = true)
        }

        val eregPath = "/ereg/api/v2/organisasjon"

        describe("Successful responses from Ereg") {
            val organization = organisasjon()
            val mockEngine = getMockEngine(
                path = "$eregPath/${organization.organisasjonsnummer}?inkluderHierarki=true",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                status = HttpStatusCode.OK,
                content = jacksonObjectMapper().writeValueAsString(organization)
            )

            val httpClient = httpClientDefault(HttpClient(mockEngine))
            val eregClient = EregClient(
                eregBaseUrl = "",
                httpClient = httpClient,
            )

            it("Fetches Organisasjon in Ereg") {
                texasHttpClient.defaultMocks()
                val result = eregClient.getOrganisasjon(organization.organisasjonsnummer)
                result shouldBe organization
            }
        }
        describe("Error responses from Ereg") {
            it("It should re-throw with internal server error if 4xx error except 404") {
                val organization = organisasjon()
                val mockEngine = getMockEngine(
                    path = "$eregPath/${organization.organisasjonsnummer}?inkluderHierarki=true",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    status = HttpStatusCode.BadRequest,
                    content = ""
                )
                val client = httpClientDefault(HttpClient(mockEngine))
                val arClient = EregClient(
                    eregBaseUrl = "",
                    httpClient = client,
                )
                shouldThrow<UpstreamRequestException> {
                    arClient.getOrganisasjon(organization.organisasjonsnummer)
                }
            }

            it("Should return null if 4xx error") {
                val organization = organisasjon()
                val mockEngine = getMockEngine(
                    path = "$eregPath/${organization.organisasjonsnummer}?inkluderHierarki=true",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    status = HttpStatusCode.NotFound,
                    content = ""
                )
                val client = httpClientDefault(HttpClient(mockEngine))
                val eregClient = EregClient(
                    eregBaseUrl = "",
                    httpClient = client,
                )
                val result = eregClient.getOrganisasjon(organization.organisasjonsnummer)
                result shouldBe null
            }
        }
    })

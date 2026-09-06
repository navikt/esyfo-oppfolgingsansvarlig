package no.nav.syfo.aareg.client

import DefaultOrganization
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import defaultMocks
import getMockEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault

class AaregClientTest :
    DescribeSpec({
        val texasHttpClient = mockk<TexasHttpClient>(relaxed = true)
        val arbeidstakerEnhet = DefaultOrganization
        val personIdent = "12345"

        describe("Successful responses from Aareg") {
            val fakeAaregClient = FakeAaregClient()
            fakeAaregClient.arbeidsForholdForIdent.put(personIdent, listOf(arbeidstakerEnhet.ID to arbeidstakerEnhet.ID))
            val arbeidsforhold = FakeAaregClient().getArbeidsforhold(personIdent)

            val mockEngine = MockEngine.Companion { req ->
                when (req.method) {
                    HttpMethod.Companion.Post -> respond(
                        content = jacksonObjectMapper().writeValueAsString(arbeidsforhold),
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )

                    else -> mockk()
                }
            }
            val httpClient = httpClientDefault(HttpClient(mockEngine))
            val aaregClient = AaregClient(
                aaregBaseUrl = "base",
                texasHttpClient = texasHttpClient,
                scope = "scope",
                httpClient = httpClient,
            )

            it("Fetches arbeidsforhold in Aareg") {
                texasHttpClient.defaultMocks()
                val res = aaregClient.getArbeidsforhold(personIdent)
                res shouldBe arbeidsforhold
                coVerify(exactly = 1) {
                    texasHttpClient.systemToken(any(), any())
                }
                res shouldBe arbeidsforhold
            }
        }
        describe("Error responses from Aareg") {

            it("It should re-throw with internal server error if 4xx error except 404") {
                texasHttpClient.defaultMocks()
                val mockEngine = getMockEngine(
                    path = AaregClient.Companion.ARBEIDSFORHOLD_OVERSIKT_PATH,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    status = HttpStatusCode.Companion.BadRequest,
                    content = ""
                )
                val client = httpClientDefault(HttpClient(mockEngine))
                val arClient = AaregClient(
                    aaregBaseUrl = "",
                    texasHttpClient = texasHttpClient,
                    scope = "scope",
                    httpClient = client,
                )
                shouldThrow<AaregClientException> {
                    arClient.getArbeidsforhold(personIdent)
                }
            }

            it("Should re-throw with internal server error if 4xx error") {
                texasHttpClient.defaultMocks()
                val mockEngine = getMockEngine(
                    path = AaregClient.Companion.ARBEIDSFORHOLD_OVERSIKT_PATH,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    status = HttpStatusCode.Companion.NotFound,
                    content = ""
                )
                val client = httpClientDefault(HttpClient(mockEngine))
                val arClient = AaregClient(
                    aaregBaseUrl = "",
                    texasHttpClient = texasHttpClient,
                    scope = "scope",
                    httpClient = client,
                )
                shouldThrow<AaregClientException> {
                    arClient.getArbeidsforhold(personIdent)
                }
            }
        }
    })

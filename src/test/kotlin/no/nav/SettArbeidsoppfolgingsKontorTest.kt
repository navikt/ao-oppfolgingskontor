package no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import domain.IdenterFunnet
import http.configureContentNegotiation
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kafka.producers.KontorEndringProducer
import no.nav.db.AktorId
import no.nav.db.Ident.HistoriskStatus.UKJENT
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorNavn
import no.nav.domain.KontorType
import no.nav.domain.NavIdent
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.HarStrengtFortroligAdresseResult
import no.nav.http.client.PdlIdenterFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.SkjermingResult
import no.nav.http.client.mockNorg2Host
import no.nav.http.client.mockPoaoTilgangHost
import no.nav.http.client.settKontor
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.installGraphQl
import no.nav.http.graphql.schemas.RegistrantTypeDto
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.utils.GraphqlResponse
import no.nav.utils.KontorTilhorighet
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonHttpClient
import no.nav.utils.getMockOauth2ServerConfig
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.gittIdentIMapping
import no.nav.utils.issueToken
import no.nav.utils.kontorTilhorighet
import no.nav.utils.kontorTilordningService
import no.nav.utils.randomAktorId
import no.nav.utils.randomFnr
import no.nav.utils.randomInternIdent
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.Partitioner
import org.apache.kafka.common.Cluster
import org.apache.kafka.common.serialization.LongSerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import services.IdentService
import services.OppfolgingsperiodeService
import java.util.UUID

val partitioner = object: Partitioner {
    override fun partition(
        topic: String?,
        key: Any?,
        keyBytes: ByteArray?,
        value: Any?,
        valueBytes: ByteArray?,
        cluster: Cluster?
    ): Int = 0
    override fun close() { TODO("Not yet implemented") }
    override fun configure(configs: Map<String?, *>?) { TODO("Not yet implemented") }
}

class SettArbeidsoppfolgingsKontorTest {

    /* application block seems to be run async so have to take block for extra db-setup as param */
    fun ApplicationTestBuilder.setupTestAppWithAuthAndGraphql(
        ident: IdentSomKanLagres,
        aktorId: AktorId,
        skjerming: SkjermingResult = SkjermingFunnet(HarSkjerming(false)),
        adressebeskyttelse: HarStrengtFortroligAdresseResult = HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
        brukAoRuting: Boolean = false,
        extraDatabaseSetup: Application.() -> Unit = {},
        ): MockProducer<Long, String?> {
        environment {
            config = server.getMockOauth2ServerConfig()
        }
        val norg2Client = mockNorg2Host()
        val poaoTilgangClient = mockPoaoTilgangHost(null)
        val kontorNavnService = KontorNavnService(norg2Client)
        val internIdent = randomInternIdent()
        val pdlIdenterFunnet = PdlIdenterFunnet(listOf(ident, aktorId), ident)
        val identerFunnet = IdenterFunnet(listOf(ident, aktorId), ident, internIdent)
        val identService = IdentService { pdlIdenterFunnet }
        val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService, identService::hentAlleIdenter)
        val oppfolgingsperiodeService = OppfolgingsperiodeService(
            identService::hentAlleIdenter,
            kontorTilordningService::slettArbeidsoppfølgingskontorTilordning
        )
        val producer = MockProducer(true, partitioner, LongSerializer(), StringSerializer())
        val kontorEndringProducer = KontorEndringProducer(
            producer,
            "arbeidsoppfolgingskontortilordninger",
            { KontorNavn("Test KontorNavn") },
            { identerFunnet },
            brukAoRuting
        )
        application {
            flywayMigrationInTest()
            extraDatabaseSetup()
            configureSecurity()
            installGraphQl(
                norg2Client,
                kontorTilhorighetService,
                { req -> req.call.authenticateCall(environment.getIssuer()) },
                identService::hentAlleIdenter,
                poaoTilgangClient::harLeseTilgang
            )
            configureContentNegotiation()
            configureArbeidsoppfolgingskontorModule(
                kontorNavnService,
                kontorTilhorighetService,
                poaoTilgangClient,
                oppfolgingsperiodeService,
                kontorTilordningService,
                { kontorEndringProducer.publiserEndringPåKontor(it) },
                hentSkjerming = { skjerming },
                hentAdresseBeskyttelse = { adressebeskyttelse },
                brukAoRuting = brukAoRuting
            )
            routing {
                authenticate("EntraAD") {
                    graphQLPostRoute()
                }
            }
        }
        return producer
    }

    @Disabled
    @Test
    fun `skal kunne sette arbeidsoppfølgingskontor`() = testApplication {
        withMockOAuth2Server {
            val fnr = randomFnr(UKJENT)
            val aktorId = randomAktorId()
            val kontorId = "4444"
            val veilederIdent = NavIdent("Z990000")
            val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
            val producer = setupTestAppWithAuthAndGraphql(fnr, aktorId) {
                gittBrukerUnderOppfolging(fnr, oppfolgingsperiodeId)
                gittIdentIMapping(fnr)
            }
            val httpClient = getJsonHttpClient()

            val response = httpClient.settKontor(server, fnr = fnr, kontorId = kontorId, navIdent = veilederIdent)

            response.status shouldBe HttpStatusCode.OK
            val readResponse = httpClient.kontorTilhorighet(fnr, server.issueToken(veilederIdent))
            readResponse.status shouldBe HttpStatusCode.OK
            val kontorResponse = readResponse.body<GraphqlResponse<KontorTilhorighet>>()
            kontorResponse.errors shouldBe null
            kontorResponse.data?.kontorTilhorighet?.kontorId shouldBe kontorId
            kontorResponse.data?.kontorTilhorighet?.registrant shouldBe veilederIdent.id
            kontorResponse.data?.kontorTilhorighet?.registrantType shouldBe RegistrantTypeDto.VEILEDER
            kontorResponse.data?.kontorTilhorighet?.kontorType shouldBe KontorType.ARBEIDSOPPFOLGING
            val firstRecord = producer.history().first()
            firstRecord.key() shouldBe oppfolgingsperiodeId.value.toString()
            firstRecord.value() shouldBe """
                {"kontorId":"${kontorId}","kontorNavn":"Test KontorNavn","oppfolgingsperiodeId":"${oppfolgingsperiodeId.value}","aktorId":"${aktorId.value}","ident":"${fnr.value}","tilordningstype":"ENDRET_KONTOR"}
            """.trimIndent()
        }
    }

    @Test
    fun `skal returnere 501 og ikke lagre noe ved setting av aokontor`() = testApplication {
        withMockOAuth2Server {
            val fnr = randomFnr(UKJENT)
            val aktorId = randomAktorId()
            val kontorId = "4444"
            val veilederIdent = NavIdent("Z990000")
            val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
            val producer = setupTestAppWithAuthAndGraphql(fnr, aktorId) {
                gittBrukerUnderOppfolging(fnr, oppfolgingsperiodeId)
                gittIdentIMapping(fnr)
            }
            val httpClient = getJsonHttpClient()

            val response = httpClient.settKontor(server, fnr = fnr, kontorId = kontorId, navIdent = veilederIdent)

            response.status shouldBe HttpStatusCode.NotImplemented
            val readResponse = httpClient.kontorTilhorighet(fnr, server.issueToken(veilederIdent))
            readResponse.status shouldBe HttpStatusCode.OK
            val kontorResponse = readResponse.body<GraphqlResponse<KontorTilhorighet>>()
            kontorResponse.errors shouldBe null
            kontorResponse.data?.kontorTilhorighet shouldBe null

            producer.history().size shouldBe 0
        }
    }

    @Test
    fun `skal svare med 409 når bruker ikke er under oppfølging`() = testApplication {
        withMockOAuth2Server {
            val fnr = randomFnr(UKJENT)
            val aktorId = randomAktorId(UKJENT)
            val kontorId = "4444"
            val veilederIdent = NavIdent("Z990000")
            setupTestAppWithAuthAndGraphql(fnr, aktorId, brukAoRuting = true) {
                gittIdentIMapping(fnr)
            }
            val httpClient = getJsonHttpClient()

            val response = httpClient.settKontor(server, fnr = fnr, kontorId = kontorId, navIdent = veilederIdent)

            response.status shouldBe HttpStatusCode.Conflict
        }
    }

    @Test
    fun `skal svare med 409 når bruker ikke er skjermet (de kan ikke flyttes)`() = testApplication {
        withMockOAuth2Server {
            val fnr = randomFnr(UKJENT)
            val aktorId = randomAktorId(UKJENT)
            val kontorId = "4444"
            val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
            val veilederIdent = NavIdent("Z990000")
            val harSkjerming = SkjermingFunnet(HarSkjerming(true))
            setupTestAppWithAuthAndGraphql(fnr, aktorId, brukAoRuting = true, skjerming = harSkjerming) {
                gittBrukerUnderOppfolging(fnr, oppfolgingsperiodeId)
                gittIdentIMapping(fnr)
            }
            val httpClient = getJsonHttpClient()

            val response = httpClient.settKontor(server, fnr = fnr, kontorId = kontorId, navIdent = veilederIdent)

            response.status shouldBe HttpStatusCode.Conflict
            response.bodyAsText() shouldBe "Kan ikke bytte kontor på skjermet bruker"
        }
    }

    @Test
    fun `skal svare med 409 når bruker ikke har strengt fortrolig adresse (de kan ikke flyttes)`() = testApplication {
        withMockOAuth2Server {
            val fnr = randomFnr(UKJENT)
            val aktorId = randomAktorId(UKJENT)
            val kontorId = "4444"
            val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
            val veilederIdent = NavIdent("Z990000")
            val adressebeskyttelse = HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
            setupTestAppWithAuthAndGraphql(fnr, aktorId, brukAoRuting = true, adressebeskyttelse = adressebeskyttelse) {
                gittBrukerUnderOppfolging(fnr, oppfolgingsperiodeId)
                gittIdentIMapping(fnr)
            }
            val httpClient = getJsonHttpClient()

            val response = httpClient.settKontor(server, fnr = fnr, kontorId = kontorId, navIdent = veilederIdent)

            response.status shouldBe HttpStatusCode.Conflict
            response.bodyAsText() shouldBe "Kan ikke bytte kontor på strengt fortrolig bruker"
        }
    }

    suspend fun withMockOAuth2Server(block: suspend MockOAuth2Server.() -> Unit) {
        server.start()
        server.block()
        server.shutdown()
    }

    val server = MockOAuth2Server()
}
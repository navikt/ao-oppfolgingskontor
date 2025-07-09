package no.nav.http.client

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import no.nav.db.Fnr
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.http.client.tokenexchange.SystemTokenPlugin
import no.nav.http.client.tokenexchange.TexasTokenResponse
import no.nav.http.graphql.generated.client.HentAdresseBeskyttelseQuery
import no.nav.http.graphql.generated.client.HentAlderQuery
import no.nav.http.graphql.generated.client.HentFnrQuery
import no.nav.http.graphql.generated.client.HentGtQuery
import no.nav.http.graphql.generated.client.enums.AdressebeskyttelseGradering
import no.nav.http.graphql.generated.client.enums.GtType
import no.nav.http.graphql.generated.client.enums.IdentGruppe
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.LocalDate
import java.time.Period
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed class AlderResult
data class AlderFunnet(val alder: Int) : AlderResult()
data class AlderIkkeFunnet(val message: String) : AlderResult()

sealed class FnrResult
data class FnrFunnet(val fnr: Fnr) : FnrResult()
data class FnrIkkeFunnet(val message: String) : FnrResult()
data class FnrOppslagFeil(val message: String) : FnrResult()

sealed class GtForBrukerResult
data class GtNummerForBrukerFunnet(val gt: GeografiskTilknytningNr) : GtForBrukerResult()
data class GtLandForBrukerFunnet(val land: GeografiskTilknytningLand) : GtForBrukerResult()
data class GtForBrukerIkkeFunnet(val message: String) : GtForBrukerResult()
data class GtForBrukerOppslagFeil(val message: String) : GtForBrukerResult()

sealed class HarStrengtFortroligAdresseResult
class HarStrengtFortroligAdresseFunnet(val harStrengtFortroligAdresse: HarStrengtFortroligAdresse) : HarStrengtFortroligAdresseResult()
class HarStrengtFortroligAdresseIkkeFunnet(val message: String) : HarStrengtFortroligAdresseResult()
class HarStrengtFortroligAdresseOppslagFeil(val message: String) : HarStrengtFortroligAdresseResult()

fun ApplicationEnvironment.getPdlScope(): String {
    return config.property("apis.pdl.scope").getString()
}

val BehandlingsnummerHeaderPlugin = createClientPlugin("BehandlingsnummerHeaderPlugin") {
    onRequest { request, _ ->
        request.headers.append("Behandlingsnummer", "B884")
    }
}

class PdlClient(
    pdlGraphqlUrl: String,
    ktorHttpClient: HttpClient
) {

    constructor(pdlGraphqlUrl: String, azureTokenProvider: suspend () -> TexasTokenResponse): this(
        pdlGraphqlUrl,
        HttpClient(CIO) {
            install(BehandlingsnummerHeaderPlugin)
            install(SystemTokenPlugin) {
                this.tokenProvider = azureTokenProvider
            }
            install(ContentNegotiation) {
                json()
            }
        }
    )

    val log = LoggerFactory.getLogger(PdlClient::class.java)
    val client = GraphQLKtorClient(
        url = URI.create("$pdlGraphqlUrl/graphql").toURL(),
        httpClient = ktorHttpClient
    )
    suspend fun hentAlder(fnr: Fnr): AlderResult {
        val query = HentAlderQuery(HentAlderQuery.Variables(fnr.value))
        val result = client.execute(query)
        if (result.errors != null && result.errors!!.isNotEmpty()) {
            return AlderIkkeFunnet(result.errors!!.joinToString { it.message })
        } else {
            val alder = result.data?.hentPerson?.foedselsdato?.firstOrNull()?.foedselsdato
                ?.let { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }
                ?.let { Period.between(ZonedDateTime.now().toLocalDate(), it).years } // TODO: Verify this is actually correct
            return if (alder == null) {
                AlderIkkeFunnet("Alder kunne ikke beregnes fra fødselsdato")
            } else {
                AlderFunnet(alder)
            }
        }
    }

    suspend fun hentFnrFraAktorId(aktorId: String): FnrResult {
        val query = HentFnrQuery(HentFnrQuery.Variables(ident = aktorId, historikk = false))
        val result = client.execute(query)
        if (result.errors != null && result.errors!!.isNotEmpty()) {
            log.error("Feil ved henting av fnr for aktorId $aktorId: \n\t${result.errors!!.joinToString { it.message }}")
            return FnrOppslagFeil(result.errors!!.joinToString { "${it.message}: ${it.extensions?.get("details")}"  })
        }
        return result.data?.hentIdenter?.identer
            ?.let { identer ->
                identer
                    .let { ids ->
                        /* Foretrekk fnr før npid */
                        ids.firstOrNull { it.gruppe == IdentGruppe.FOLKEREGISTERIDENT && !it.historisk }
                            ?: ids.firstOrNull { it.gruppe == IdentGruppe.NPID && !it.historisk }
                    }
                    ?.ident
                    ?.let { FnrFunnet(Fnr(it)) }
                    ?: run {
                        log.debug("Fant ${identer.size} på identer")
                        FnrIkkeFunnet("Fant ingen gyldig fnr for bruker, antall identer: ${identer.size}, indent-typer: ${identer.joinToString { it.gruppe.name }}")
                    }
            } ?: FnrIkkeFunnet("Ingen ident funnet, feltet `identer` i hentIdenter response var null")
    }

    suspend fun hentGt(fnr: Fnr): GtForBrukerResult {
        try {
            val query = HentGtQuery(HentGtQuery.Variables(ident = fnr.value))
            val result = client.execute(query)
            if (result.errors != null && result.errors!!.isNotEmpty()) {
                log.error("Feil ved henting av gt for bruker: \n\t${result.errors!!.joinToString { it.message }}")
                return GtForBrukerOppslagFeil(result.errors!!.joinToString { "${it.message}: ${it.extensions?.get("details")}"  })
            }
            return result.toGeografiskTilknytning()
        } catch (e: Throwable) {
            return GtForBrukerOppslagFeil("Henting av GT for bruker feilet: ${e.message ?: e.toString()}")
                .also { log.error(it.message, e) }
        }
    }

    suspend fun harStrengtFortroligAdresse(fnr: Fnr): HarStrengtFortroligAdresseResult {
        try {
            val query = HentAdresseBeskyttelseQuery(HentAdresseBeskyttelseQuery.Variables(fnr.value, false))
            val result = client.execute(query)
            if (result.errors != null && result.errors!!.isNotEmpty()) {
                log.error("Feil ved henting av strengt fortrolig adresse for bruker: \n\t${result.errors!!.joinToString { it.message }}")
                return HarStrengtFortroligAdresseOppslagFeil(result.errors!!.joinToString { "${it.message}: ${it.extensions?.get("details")}"  })
            }
            return result?.data?.hentPerson?.adressebeskyttelse
                ?.also {
                    if (it.isEmpty()) return HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
                }
                ?.firstOrNull()
                ?.let { it.gradering == AdressebeskyttelseGradering.STRENGT_FORTROLIG || it.gradering == AdressebeskyttelseGradering.STRENGT_FORTROLIG_UTLAND }
                ?.let { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(it)) }
                ?: HarStrengtFortroligAdresseIkkeFunnet("Ingen adressebeskyttelse funnet for bruker $result")
        } catch (e: Throwable) {
            log.error("Henting av strengt fortrolig adresse for bruker feilet: ${e.message ?: e.toString()}", e)
            return HarStrengtFortroligAdresseOppslagFeil("Henting av strengt fortrolig adresse for bruker feilet: ${e.message ?: e.toString()}")
        }
    }
}

fun GraphQLClientResponse<HentGtQuery.Result>.toGeografiskTilknytning(): GtForBrukerResult {
    return this.data?.hentGeografiskTilknytning?.let {
            when (it.gtType) {
                GtType.BYDEL -> it.gtBydel?.let { bydel -> GeografiskTilknytningNr(bydel) }
                GtType.KOMMUNE -> it.gtKommune?.let { kommune -> GeografiskTilknytningNr(kommune) }
                GtType.UTLAND -> it.gtLand?.let { land -> return GtLandForBrukerFunnet(GeografiskTilknytningLand(land)) }
                else -> null
            }?.let { gt -> GtNummerForBrukerFunnet(gt) }
                ?: GtForBrukerIkkeFunnet("Ingen gyldige verider i GT repons fra PDL funnet for type ${it.gtType} bydel: ${it.gtBydel}, kommune: ${it.gtKommune}, land: ${it.gtLand}")
        } ?: GtForBrukerIkkeFunnet("Ingen geografisk tilknytning funnet for bruker $this")
}

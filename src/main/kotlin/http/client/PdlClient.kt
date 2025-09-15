package no.nav.http.client

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.api.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import no.nav.db.*
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.http.client.tokenexchange.SystemTokenPlugin
import no.nav.http.client.tokenexchange.TexasTokenResponse
import no.nav.http.graphql.generated.client.HentAdresseBeskyttelseQuery
import no.nav.http.graphql.generated.client.HentAlderQuery
import no.nav.http.graphql.generated.client.HentFnrQuery
import no.nav.http.graphql.generated.client.HentGtQuery
import no.nav.http.graphql.generated.client.enums.AdressebeskyttelseGradering
import no.nav.http.graphql.generated.client.enums.GtType
import no.nav.http.graphql.generated.client.hentfnrquery.IdentInformasjon
import org.slf4j.LoggerFactory
import services.toKnownHistoriskStatus
import java.net.URI
import java.time.LocalDate
import java.time.Period
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed class AlderResult
data class AlderFunnet(val alder: Int) : AlderResult()
data class AlderIkkeFunnet(val message: String) : AlderResult()
data class AlderOppslagFeil(val message: String) : AlderResult()

sealed class IdentResult
data class IdentFunnet(val ident: Ident) : IdentResult()
data class IdentIkkeFunnet(val message: String) : IdentResult()
data class IdentOppslagFeil(val message: String) : IdentResult()

sealed class IdenterResult
data class IdenterFunnet(val identer: List<Ident>, val inputIdent: Ident, val foretrukketIdent: Ident) : IdenterResult() {
    constructor(identer: List<Ident>, inputIdent: Ident): this(
        identer = identer,
        inputIdent = inputIdent,
        foretrukketIdent = identer.finnForetrukketIdent() ?: throw IllegalStateException("Fant ikke foretrukket ident, alle identer historiske?")
    )
}
data class IdenterIkkeFunnet(val message: String) : IdenterResult()
data class IdenterOppslagFeil(val message: String) : IdenterResult()

sealed class GtForBrukerResult
sealed class GtForBrukerSuccess : GtForBrukerResult()
sealed class GtForBrukerFunnet : GtForBrukerSuccess()
data class GtNummerForBrukerFunnet(val gtNr: GeografiskTilknytningNr) : GtForBrukerFunnet() {
    override fun toString() = "${gtNr.value} type: ${gtNr.type.name}"
}

data class GtLandForBrukerFunnet(val land: GeografiskTilknytningLand) : GtForBrukerFunnet() {
    override fun toString() = "${land.value} type: Land"
}

data class GtForBrukerIkkeFunnet(val message: String) : GtForBrukerSuccess()
data class GtForBrukerOppslagFeil(val message: String) : GtForBrukerResult()

sealed class HarStrengtFortroligAdresseResult
class HarStrengtFortroligAdresseFunnet(val harStrengtFortroligAdresse: HarStrengtFortroligAdresse) :
    HarStrengtFortroligAdresseResult()

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

    constructor(pdlGraphqlUrl: String, azureTokenProvider: suspend () -> TexasTokenResponse) : this(
        pdlGraphqlUrl,
        HttpClient(CIO) {
            install(BehandlingsnummerHeaderPlugin)
            install(SystemTokenPlugin) {
                this.tokenProvider = azureTokenProvider
            }
            install(ContentNegotiation) {
                json()
            }
            install(Logging) {
                level = LogLevel.INFO
            }
        }
    )

    val log = LoggerFactory.getLogger(PdlClient::class.java)
    val client = GraphQLKtorClient(
        url = URI.create("$pdlGraphqlUrl/graphql").toURL(),
        httpClient = ktorHttpClient
    )

    suspend fun hentAlder(fnr: Ident): AlderResult {
        try {
            val query = HentAlderQuery(HentAlderQuery.Variables(fnr.value))
            val result = client.execute(query)
            if (result.errors != null && result.errors!!.isNotEmpty()) {
                return AlderIkkeFunnet(result.errors!!.joinToString { it.message })
            } else {
                val foedselsdatoObject = result.data?.hentPerson?.foedselsdato?.firstOrNull()
                    ?: return AlderIkkeFunnet("Ingen foedselsdato i felt 'foedselsdato' fra pdl-spørring, dårlig data i dev?")
                val foedselsdato = foedselsdatoObject.foedselsdato
                    ?.let { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }
                val now = ZonedDateTime.now().toLocalDate()
                val alder = foedselsdato
                    ?.let { Period.between(it, now).years }
                // Fallback to foedselsaar if foedselsdato does not exist
                    ?: foedselsdatoObject.foedselsaar?.let { now.year - it }
                return if (alder == null) {
                    AlderIkkeFunnet("Alder kunne ikke beregnes fra fødselsdato")
                } else {
                    AlderFunnet(alder)
                }
            }
        } catch (e: Throwable) {
            return AlderOppslagFeil("Henting av alder feilet: ${e.message}")
                .also { log.error(it.message, e) }
        }
    }

    suspend fun hentIdenterFor(aktorId: String): IdenterResult {
        try {
            val query = HentFnrQuery(HentFnrQuery.Variables(ident = aktorId, historikk = true))
            val result = client.execute(query)
            if (result.errors != null && result.errors!!.isNotEmpty()) {
                return IdenterOppslagFeil(result.errors!!.joinToString { "${it.message}: ${it.extensions?.get("code")}" })
            }
            return result.data?.hentIdenter?.identer?.let { pdlIdenter ->
                val identer = pdlIdenter.map { (ident, historisk) -> Ident.of(ident, historisk.toKnownHistoriskStatus()) }
                val inputIdent = identer.first { it.value == aktorId }
                val foretrukketIdent = identer.finnForetrukketIdent() ?: throw IllegalStateException("Fant ingen foretrukket ident på bruker, er alle identer historiske?")
                IdenterFunnet(identer, inputIdent, foretrukketIdent)
            } ?: IdenterIkkeFunnet("Ingen identer funnet for aktorId")

        } catch (e: Throwable) {
            return IdenterOppslagFeil("Henting av fnr fra aktorId feilet: ${e.message}")
                .also { log.error(it.message, e) }
        }
    }

    suspend fun hentGt(fnr: Ident): GtForBrukerResult {
        try {
            val query = HentGtQuery(HentGtQuery.Variables(ident = fnr.value))
            val result = client.execute(query)
            if (result.errors != null && result.errors!!.isNotEmpty()) {
                log.error("Feil ved henting av gt for bruker: \n\t${result.errors!!.joinToString { it.message }}")
                return GtForBrukerOppslagFeil(result.errors!!.joinToString { "${it.message}: ${it.extensions?.get("details")}" })
            }
            return result.toGeografiskTilknytning()
        } catch (e: Throwable) {
            return GtForBrukerOppslagFeil("Henting av GT for bruker feilet: ${e.message ?: e.toString()}")
                .also { log.error(it.message, e) }
        }
    }

    suspend fun harStrengtFortroligAdresse(fnr: Ident): HarStrengtFortroligAdresseResult {
        try {
            val query = HentAdresseBeskyttelseQuery(HentAdresseBeskyttelseQuery.Variables(fnr.value, false))
            val result = client.execute(query)
            if (result.errors != null && result.errors!!.isNotEmpty()) {
                log.error("Feil ved henting av strengt fortrolig adresse for bruker: \n\t${result.errors!!.joinToString { it.message }}")
                return HarStrengtFortroligAdresseOppslagFeil(result.errors!!.joinToString {
                    "${it.message}: ${
                        it.extensions?.get(
                            "details"
                        )
                    }"
                })
            }
            return result.data?.hentPerson?.adressebeskyttelse
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
                .also { log.error(it.message, e) }
        }
    }
}

fun GraphQLClientResponse<HentGtQuery.Result>.toGeografiskTilknytning(): GtForBrukerResult {
    return this.data?.hentGeografiskTilknytning?.let {
        when (it.gtType) {
            GtType.BYDEL -> it.gtBydel?.let { bydel -> GeografiskTilknytningBydelNr(bydel) }
            GtType.KOMMUNE -> it.gtKommune?.let { kommune -> GeografiskTilknytningKommuneNr(kommune) }
            GtType.UTLAND -> it.gtLand?.let { land -> return GtLandForBrukerFunnet(GeografiskTilknytningLand(land)) }
            else -> null
        }?.let { gt -> GtNummerForBrukerFunnet(gt) }
            ?: GtForBrukerIkkeFunnet("Ingen gyldige verider i GT repons fra PDL funnet for type ${it.gtType} bydel: ${it.gtBydel}, kommune: ${it.gtKommune}, land: ${it.gtLand}")
    } ?: GtForBrukerIkkeFunnet("Ingen geografisk tilknytning funnet for bruker $this")
}

fun IdenterResult.finnForetrukketIdent(): IdentResult {
    return when (this) {
        is IdenterFunnet -> {
            this.identer.finnForetrukketIdent()
                ?.let { IdentFunnet(it) }
                ?: IdentIkkeFunnet("Fant ingen gyldig fnr for bruker, antall identer: ${this.identer.size}, indent-typer: ${this.identer.joinToString { it::class.java.simpleName }}")
        }
        is IdenterIkkeFunnet -> IdentIkkeFunnet(this.message)
        is IdenterOppslagFeil -> IdentOppslagFeil(this.message)
    }
}
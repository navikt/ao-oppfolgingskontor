package no.nav.http.client

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.services.KontorForBrukerMedMangelfullGtFeil
import no.nav.services.KontorForBrukerMedMangelfullGtFunnet
import no.nav.services.KontorForBrukerMedMangelfullGtIkkeFunnet
import no.nav.services.KontorForBrukerMedMangelfullGtResultat
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorForGtFeil
import no.nav.services.KontorForGtResultat
import org.slf4j.LoggerFactory

class Norg2Client(
    val baseUrl: String,
    val httpClient: HttpClient = HttpClient(CIO) {
        defaultRequest {
            url(baseUrl)
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(ContentNegotiation) {
            json()
        }
    }
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun hentAlleEnheter(): List<MinimaltNorgKontor> {
        val response = httpClient.get(hentEnheterPath) {
            accept(ContentType.Application.Json)
        }
        return response.body<List<NorgKontor>>()
            .filter { it.type == "LOKAL" }
            .map { it.toMinimaltKontor() }
    }

    suspend fun hentKontor(kontorId: KontorId): MinimaltNorgKontor {
        val response = httpClient.get(hentEnhetPath(kontorId)) {
            accept(ContentType.Application.Json)
        }
        if (response.status != HttpStatusCode.OK)
            throw RuntimeException("Kunne ikke hente kontor med id $kontorId fra Norg2. Status: ${response.status}")
        return response.body<NorgKontor>().toMinimaltKontor()
    }

    suspend fun hentKontorForGt(gt: GeografiskTilknytningNr, brukerHarStrengtFortroligAdresse: HarStrengtFortroligAdresse, brukerErSkjermet: HarSkjerming): KontorForGtResultat {
        try {
            val response = httpClient.get((hentKontorForGtPath(gt))) {
                accept(ContentType.Application.Json)
                if (brukerHarStrengtFortroligAdresse.value) {
                    parameter("disk", "SPSF")
                }
                if (brukerErSkjermet.value) {
                    parameter("skjermet", "true")
                }
            }
            if (response.status != HttpStatusCode.OK)
                throw RuntimeException("Kunne ikke hente kontor for GT i norg, http-status: ${response.status}, gt: ${gt.value} ${gt.type}")
            return response.body<NorgKontor>().toMinimaltKontor()
                .let {
                    KontorId(it.kontorId).toDefaultGtKontorFunnet(
                        brukerHarStrengtFortroligAdresse,
                        brukerErSkjermet,
                        gt)
                }
        } catch (e: Throwable) {
            return KontorForGtFeil(e.message ?: "Ukjent feil")
        }
    }

    @Serializable
    data class ArbeidsfordelingPayload(
        val diskresjonskode: String?,
        val geografiskOmraade: String?,
        val skjermet: Boolean,
        val tema: String = "OPP",
        val behandlingstype: String = "ae0253", // "Oppfølgingskontor"
    )

    suspend fun hentKontorForBrukerMedMangelfullGT(gtForBruker: GtForBrukerResult, brukerHarStrengtFortroligAdresse: HarStrengtFortroligAdresse, brukerErSkjermet: HarSkjerming): KontorForBrukerMedMangelfullGtResultat {
        return when (gtForBruker) {
            is GtForBrukerOppslagFeil -> return KontorForBrukerMedMangelfullGtFeil(gtForBruker.message)
            is GtForBrukerSuccess -> _hentKontorForBrukerMedMangelfullGT(
                gtForBruker,
                brukerHarStrengtFortroligAdresse,
                brukerErSkjermet,
            )
        }
    }

    private suspend fun _hentKontorForBrukerMedMangelfullGT(gtForBruker: GtForBrukerSuccess, brukerHarStrengtFortroligAdresse: HarStrengtFortroligAdresse, brukerErSkjermet: HarSkjerming): KontorForBrukerMedMangelfullGtResultat {
        try {
            val geografiskOmraade = when (gtForBruker) {
                is GtLandForBrukerFunnet -> gtForBruker.land.value
                is GtNummerForBrukerFunnet -> gtForBruker.gtNr.value
                is GtForBrukerIkkeFunnet -> null
            }
            val response = httpClient.post(arbeidsfordelingPath) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                setBody(
                    ArbeidsfordelingPayload(
                        geografiskOmraade = geografiskOmraade,
                        skjermet = brukerErSkjermet.value,
                        diskresjonskode = if (brukerHarStrengtFortroligAdresse.value) "SPSF" else null,
                    )
                )
            }
            if (response.status != HttpStatusCode.OK)
                throw RuntimeException("Kunne ikke hente kontor for GT i norg med arbeidsfordeling, http-status: ${response.status}, gt: $gtForBruker")

            return response.body<List<NorgKontor>>()
                .firstOrNull()
                ?.let { KontorForBrukerMedMangelfullGtFunnet(KontorId(it.toMinimaltKontor().kontorId), gtForBruker) }
                ?: KontorForBrukerMedMangelfullGtIkkeFunnet(gtForBruker)
        } catch (e: Throwable) {
            return KontorForBrukerMedMangelfullGtFeil("Kunne ikke hente kontor for GT i norg med arbeidsfordeling ${e.message}")
        }
    }

        companion object {
        const val hentEnheterPath = "/norg2/api/v1/enhet"
        const val hentEnhetPathWithParam = "/norg2/api/v1/enhet/{enhetId}"
        fun hentEnhetPath(kontorId: KontorId): (String) = "/norg2/api/v1/enhet/${kontorId.id}"
        fun hentKontorForGtPath(gt: GeografiskTilknytningNr): (String) = "/norg2/api/v1/enhet/navkontor/${gt.value}"
        const val arbeidsfordelingPath = "/norg2/api/v1/arbeidsfordeling/enheter/bestmatch"
    }
}

fun KontorId.toDefaultGtKontorFunnet(
    brukerHarStrengtFortroligAdresse: HarStrengtFortroligAdresse,
    brukerErSkjermet: HarSkjerming,
    geografiskTilknytningNr: GeografiskTilknytningNr,
    ): KontorForGtNrFantDefaultKontor {
    return KontorForGtNrFantDefaultKontor(
        this,
        brukerErSkjermet,
        brukerHarStrengtFortroligAdresse,
        geografiskTilknytningNr = geografiskTilknytningNr
    )
}

data class MinimaltNorgKontor(
    val kontorId: String,
    val navn: String
)

enum class GtType {
    Bydel,
    Kommune
}

sealed class GeografiskTilknytningNr(open val value: String, val type: GtType)
data class GeografiskTilknytningBydelNr(override val value: String): GeografiskTilknytningNr(value, GtType.Bydel)
data class GeografiskTilknytningKommuneNr(override val value: String): GeografiskTilknytningNr(value, GtType.Bydel)
data class GeografiskTilknytningLand(val value: String)

fun NorgKontor.toMinimaltKontor() = MinimaltNorgKontor(
    kontorId = this.enhetNr,
    navn = this.navn
)

@Serializable
data class NorgKontor(
    val enhetId: Long,
    val navn: String,
    val enhetNr: String,
    val antallRessurser: Int,
    val status: String,
    val orgNivaa: String,
    val type: String,
    val organisasjonsnummer: String?,
    val underEtableringDato: String?,
    val aktiveringsdato: String?,
    val underAvviklingDato: String?,
    val nedleggelsesdato: String?,
    val oppgavebehandler: Boolean,
    val versjon: Int,
    val sosialeTjenester: String?,
    val kanalstrategi: String?,
    val orgNrTilKommunaltNavKontor: String?
)

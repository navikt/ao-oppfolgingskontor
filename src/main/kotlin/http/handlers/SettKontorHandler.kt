package http.handlers

import io.ktor.http.HttpStatusCode
import no.nav.AOPrincipal
import no.nav.db.AktorId
import no.nav.db.Dnr
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.db.Npid
import no.nav.domain.ArbeidsoppfolgingsKontor
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.KontorTilordning
import no.nav.domain.events.KontorEndretEvent
import no.nav.domain.events.KontorSattAvVeileder
import no.nav.http.ArbeidsoppfolgingsKontorTilordningDTO
import no.nav.http.Kontor
import no.nav.http.KontorByttetOkResponseDto
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.HarStrengtFortroligAdresseIkkeFunnet
import no.nav.http.client.HarStrengtFortroligAdresseOppslagFeil
import no.nav.http.client.HarStrengtFortroligAdresseResult
import no.nav.http.client.IdentFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.SkjermingIkkeFunnet
import no.nav.http.client.SkjermingResult
import no.nav.http.client.poaoTilgang.HarIkkeTilgangTilBruker
import no.nav.http.client.poaoTilgang.HarTilgangTilBruker
import no.nav.http.client.poaoTilgang.TilgangTilBrukerOppslagFeil
import no.nav.http.client.poaoTilgang.TilgangTilBrukerResult
import no.nav.http.logger
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeOppslagResult
import no.nav.toRegistrant
import org.slf4j.LoggerFactory
import arrow.core.Either
import arrow.core.flatMap
import arrow.core.flatten
import arrow.core.getOrElse
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.poaoTilgang.HarIkkeTilgangTilKontor
import no.nav.http.client.poaoTilgang.HarTilgangTilKontor
import no.nav.http.client.poaoTilgang.TilgangTilKontorOppslagFeil
import no.nav.http.client.poaoTilgang.TilgangTilKontorResult

sealed class SettKontorResult
data class SettKontorSuccess(val response: KontorByttetOkResponseDto) : SettKontorResult()
data class SettKontorFailure(val statusCode: HttpStatusCode, val message: String) : SettKontorResult()

class SettKontorHandler(
    private val hentKontorNavn: suspend (KontorId) -> KontorNavn,
    private val hentAoKontor: suspend (IdentSomKanLagres) -> ArbeidsoppfolgingsKontor?,
    private val harLeseTilgangTilBruker: suspend (AOPrincipal, IdentSomKanLagres, String) -> TilgangTilBrukerResult,
    private val harTilgangTilKontor: suspend (AOPrincipal, KontorId, String) -> TilgangTilKontorResult,
    private val hentOppfolgingsPeriode: (IdentFunnet) -> OppfolgingsperiodeOppslagResult,
    private val tilordneKontor: (KontorEndretEvent, Boolean) -> Unit,
    private val publiserKontorEndring: suspend (KontorSattAvVeileder) -> Result<Unit>,
    private val hentSkjerming: suspend (IdentSomKanLagres) -> SkjermingResult,
    private val hentAdresseBeskyttelse: suspend (IdentSomKanLagres) -> HarStrengtFortroligAdresseResult,
    private val brukAoRuting: Boolean,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private fun validateIdent(ident: String): Either<SettKontorFailure, IdentSomKanLagres>  {
        return Either.catch { Ident.validateOrThrow(ident, Ident.HistoriskStatus.UKJENT) }
            .mapLeft { SettKontorFailure(HttpStatusCode.BadRequest, "Kunne ikke sette kontor, ident var ikke gyldig") }
            .map { muligLagrebarIdent ->
                return when (muligLagrebarIdent) {
                    is AktorId -> Either.Left(
                        SettKontorFailure(HttpStatusCode.BadRequest, "/api/kontor støtter ikke endring via aktorId, bruk dnr/fnr istedet")
                    )
                    is Dnr, is Fnr, is Npid -> Either.Right(muligLagrebarIdent)
                }
            }
    }

    private suspend fun sjekkHarTilgang(principal: AOPrincipal, ident: IdentSomKanLagres, kontorId: KontorId, traceId: String): Either<SettKontorFailure, Unit> {
        val harTilgangTilBruker = harLeseTilgangTilBruker(principal, ident, traceId)
        val harTilgangTilKontor = harTilgangTilKontor(principal, kontorId, traceId)

        if(harTilgangTilBruker is HarTilgangTilBruker || harTilgangTilKontor is HarTilgangTilKontor) {
            return Either.Right(Unit)
        }

        val tilgangTilBrukerFeil = when (harTilgangTilBruker) {
            is HarIkkeTilgangTilBruker -> "Du har ikke tilgang til å endre kontor for bruker"
            is TilgangTilBrukerOppslagFeil -> "Noe gikk galt under oppslag av tilgang for bruker: ${harTilgangTilBruker.message}"
            else -> null
        }
        val tilgangTilKontorFeil = when (harTilgangTilKontor) {
            is HarIkkeTilgangTilKontor -> "Du har ikke tilgang til å endre kontor for bruker til ønsket kontor"
            is TilgangTilKontorOppslagFeil -> "Noe gikk galt under oppslag av tilgang til kontor: ${harTilgangTilKontor.message}"
            else -> null
        }

        val statusCode = if (harTilgangTilBruker is TilgangTilBrukerOppslagFeil || harTilgangTilKontor is TilgangTilKontorOppslagFeil) {
            HttpStatusCode.InternalServerError
        } else {
            HttpStatusCode.Forbidden
        }
        val errorMessage = listOfNotNull(tilgangTilBrukerFeil, tilgangTilKontorFeil).joinToString("; ")
        logger.warn(errorMessage)
        return Either.Left(SettKontorFailure(statusCode, errorMessage))
    }

    private fun hentOppfolgingsperiode(ident: IdentSomKanLagres): Either<SettKontorFailure, OppfolgingsperiodeId> {
        val oppfolgingsperiode = hentOppfolgingsPeriode(IdentFunnet(ident))
        return when (oppfolgingsperiode) {
            is AktivOppfolgingsperiode -> Either.Right(oppfolgingsperiode.periodeId)
            NotUnderOppfolging -> {
                Either.Left(SettKontorFailure(HttpStatusCode.Conflict, "Bruker er ikke under oppfølging"))
            }
            is OppfolgingperiodeOppslagFeil -> {
                log.error("Klarte ikke hente oppfølgingsperiode: ${oppfolgingsperiode.message}")
                Either.Left(SettKontorFailure(HttpStatusCode.InternalServerError, "Klarte ikke hente oppfølgingsperiode"))
            }
        }
    }

    private suspend fun sjekkBrukerHarIkkeAdressebeskyttelse(ident: IdentSomKanLagres): Either<SettKontorFailure, Unit> {
        return when(val adressebeskyttelse = hentAdresseBeskyttelse(ident)) {
            is HarStrengtFortroligAdresseFunnet -> {
                if (adressebeskyttelse.harStrengtFortroligAdresse.value) {
                    val errorMessage = "Kan ikke bytte kontor på strengt fortrolig bruker"
                    log.error(errorMessage)
                    return Either.Left(SettKontorFailure(HttpStatusCode.Conflict, errorMessage))
                } else Either.Right(Unit)
            }
            is HarStrengtFortroligAdresseIkkeFunnet -> {
                log.error("Fant ikke adressebeskyttelse ved flytting av bruker: ${adressebeskyttelse.message}")
                return Either.Left(SettKontorFailure(HttpStatusCode.InternalServerError, adressebeskyttelse.message))
            }
            is HarStrengtFortroligAdresseOppslagFeil -> {
                log.error("Fant ikke adressebeskyttelse ved flytting av bruker: ${adressebeskyttelse.message}")
                return Either.Left(SettKontorFailure(HttpStatusCode.InternalServerError, adressebeskyttelse.message))
            }
        }
    }

    private suspend fun sjekkBrukerErIkkeSkjermet(ident: IdentSomKanLagres): Either<SettKontorFailure, Unit> {
        return when(val skjerming = hentSkjerming(ident)) {
            is SkjermingFunnet -> {
                if (skjerming.skjermet.value) {
                    val errorMessage = "Kan ikke bytte kontor på skjermet bruker"
                    log.error(errorMessage)
                    Either.Left(SettKontorFailure(HttpStatusCode.Conflict, errorMessage))
                } else Either.Right(Unit)
            }
            is SkjermingIkkeFunnet -> {
                log.error("Fant ikke skjerming ved flytting av bruker: ${skjerming.melding}")
                Either.Left(SettKontorFailure(HttpStatusCode.InternalServerError, skjerming.melding))
            }
        }
    }

    suspend fun settKontor(tilordning: ArbeidsoppfolgingsKontorTilordningDTO, principal: AOPrincipal, traceId: String): SettKontorResult {
        if(!brukAoRuting) {
            return SettKontorFailure(HttpStatusCode.NotImplemented, "Kan ikke sette kontor for vi er i prod")
        } else {
            return Either.catch {
                validateIdent(tilordning.ident)
                    .flatMap { ident -> sjekkHarTilgang(principal, ident, KontorId(tilordning.kontorId), traceId).map { ident } }
                    .flatMap { ident -> sjekkBrukerHarIkkeAdressebeskyttelse(ident).map { ident } }
                    .flatMap { ident -> sjekkBrukerErIkkeSkjermet(ident).map { ident } }
                    .flatMap { ident -> hentOppfolgingsperiode(ident).map { it to ident } }
                    .map { (periodeId: OppfolgingsperiodeId, ident: IdentSomKanLagres) ->
                        val gammeltKontor = hentAoKontor(ident)
                        val kontorId = KontorId(tilordning.kontorId)

                        val kontorEndring = KontorSattAvVeileder(
                            tilhorighet = KontorTilordning(ident, kontorId, periodeId),
                            registrant = principal.toRegistrant()
                        )
                        tilordneKontor(kontorEndring, brukAoRuting)
                        val result = publiserKontorEndring(kontorEndring)
                        if (result.isFailure) throw result.exceptionOrNull()!!
                        kontorId to gammeltKontor
                    }
                    .map { (kontorId, gammeltKontor) ->
                        val kontorNavn = Either.catch { hentKontorNavn(kontorId) }.getOrElse { KontorNavn("<Ukjent kontornavn>") }
                        val response = buildResponse(kontorNavn, kontorId, gammeltKontor)
                        SettKontorSuccess(response)
                    }
            }
                .mapLeft { error ->
                    logger.error("Kunne ikke oppdatere kontor", error)
                    SettKontorFailure(HttpStatusCode.InternalServerError, "Kunne ikke oppdatere kontor")
                }
                .flatten()
                .fold({ it }, { it })
        }
    }
}

fun buildResponse(kontorNavn: KontorNavn, kontorId: KontorId, gammeltKontor: ArbeidsoppfolgingsKontor?) = KontorByttetOkResponseDto(
    fraKontor = gammeltKontor?.let {
        Kontor(
            kontorNavn = it.kontorNavn.navn,
            kontorId = it.kontorId.id,
        )
    },
    tilKontor = Kontor(
        kontorNavn = kontorNavn.navn,
        kontorId = kontorId.id
    )
)

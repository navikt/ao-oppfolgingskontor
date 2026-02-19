package kafka.producers

import domain.IdenterFunnet
import domain.IdenterIkkeFunnet
import domain.IdenterOppslagFeil
import domain.IdenterResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.db.InternIdent
import no.nav.db.finnForetrukketIdent
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.KontorSattAvVeileder
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import services.KontortilordningSomSkalRepubliseres

class KontorEndringProducer(
    val producer: Producer<Long, String?>,
    val kontorTopicNavn: String,
    val kontorNavnProvider: suspend (kontorId: KontorId) -> KontorNavn,
    val hentAlleIdenter: suspend (identInput: IdentSomKanLagres) -> IdenterResult,
    val brukAoRuting: Boolean
) {

    /**
    * Brukes ved synkront endring via REST API
    * */
    suspend fun publiserEndringPåKontor(event: KontorSattAvVeileder): Result<Unit> {
        if (brukAoRuting) {
            return runCatching {
                val (ident, aktorId, internIdent) = finnPubliseringsIdenter(event.tilordning.fnr)
                val value = event.toKontorTilordningMeldingDto(
                    aktorId,
                    ident,
                    kontorNavnProvider(event.tilordning.kontorId)
                )
                publiserEndringPåKontor(internIdent, value)
            }
        } else {
            return Result.success(Unit)
        }
    }

    suspend fun publiserEndringPåKontor(event: OppfolgingEndretTilordningMelding): Result<Unit> {
        return runCatching {
            val ident = Ident.validateOrThrow(event.ident, Ident.HistoriskStatus.UKJENT) as? IdentSomKanLagres
                ?: throw IllegalArgumentException("Kan ikke publisere kontor-endring på aktørid, trenger annen ident")
            val (fnr, aktorId, internIdent) = finnPubliseringsIdenter(ident)

            publiserEndringPåKontor(
                internIdent,
                KontorTilordningMeldingDto(
                    kontorId = event.kontorId,
                    kontorNavn = kontorNavnProvider(KontorId(event.kontorId)).navn,
                    oppfolgingsperiodeId = event.oppfolgingsperiodeId,
                    aktorId = aktorId.value,
                    ident = fnr.value,
                    tilordningstype = Tilordningstype.fraKontorEndringsType(event.kontorEndringsType)
                )
            )
        }
    }

    fun republiserKontor(kontortilordningSomSkalRepubliseres: KontortilordningSomSkalRepubliseres): Result<Unit> {
        val value = kontortilordningSomSkalRepubliseres.toKontorTilordningMeldingDto()
        return publiserEndringPåKontor(kontortilordningSomSkalRepubliseres.internIdent, value)
    }

    private fun publiserEndringPåKontor(internIdent: InternIdent, event: KontorTilordningMeldingDto): Result<Unit> {
        return runCatching {
            val record = ProducerRecord(
                kontorTopicNavn,
                internIdent.value,
                Json.encodeToString(event)
            )
            producer.send(record)
        }
    }

    fun publiserTombstone(internIdent: InternIdent): Result<Unit> {
        return runCatching {
            val record: ProducerRecord<Long, String?> = ProducerRecord(
                kontorTopicNavn,
                internIdent.value,
                null
            )
            producer.send(record)
        }
    }

    suspend fun finnPubliseringsIdenter(ident: IdentSomKanLagres): Triple<IdentSomKanLagres, AktorId, InternIdent> {
        val alleIdenter = hentAlleIdenter(ident)
        val (identer, internIdent) = when (alleIdenter) {
            is IdenterFunnet -> alleIdenter.identer to alleIdenter.internIdent
            is IdenterIkkeFunnet -> throw RuntimeException("Finner ikke identer for ident")
            is IdenterOppslagFeil -> throw RuntimeException("Feil ved oppslag av identer")
        }
        val aktorId = identer.first { it is AktorId && it.historisk == Ident.HistoriskStatus.AKTIV } as? AktorId
            ?: throw RuntimeException("Fant ikke aktorId for ident ved publisering av kontor endring")
        val fnr = identer.finnForetrukketIdent() ?: throw RuntimeException("Fant ikke foretrukken ident for $ident")
        return  Triple(fnr, aktorId, internIdent)
    }
}

fun AOKontorEndret.toKontorTilordningMeldingDto(
    aktorId: AktorId,
    ident: IdentSomKanLagres,
    kontorNavn: KontorNavn
): KontorTilordningMeldingDto {
    return KontorTilordningMeldingDto(
        kontorId = this.tilordning.kontorId.id,
        kontorNavn = kontorNavn.navn,
        oppfolgingsperiodeId = this.tilordning.oppfolgingsperiodeId.value.toString(),
        aktorId = aktorId.value,
        ident = ident.value,
        tilordningstype = Tilordningstype.fraKontorEndringsType(this.kontorEndringsType())
    )
}

fun KontortilordningSomSkalRepubliseres.toKontorTilordningMeldingDto(): KontorTilordningMeldingDto {
    return KontorTilordningMeldingDto(
        kontorId = this.kontorId.id,
        kontorNavn = this.kontorNavn.navn,
        oppfolgingsperiodeId = this.oppfolgingsperiodeId.value.toString(),
        aktorId = this.aktorId.value,
        ident = this.ident.value,
        tilordningstype = Tilordningstype.fraKontorEndringsType(this.kontorEndringsType)
    )
}


fun AOKontorEndret.toKontorTilordningMelding(): OppfolgingEndretTilordningMelding {
    return OppfolgingEndretTilordningMelding(
        kontorId = this.tilordning.kontorId.id,
        oppfolgingsperiodeId = this.tilordning.oppfolgingsperiodeId.value.toString(),
        ident = this.tilordning.fnr.value,
        kontorEndringsType = this.kontorEndringsType()
    )
}

@Serializable
data class KontorTilordningMeldingDto(
    val kontorId: String,
    val kontorNavn: String,
    val oppfolgingsperiodeId: String,
    val aktorId: String,
    val ident: String,
    val tilordningstype: Tilordningstype,
)

enum class Tilordningstype {
    KONTOR_VED_OPPFOLGINGSPERIODE_START,
    ENDRET_KONTOR;

    // TODO: Midlertidig tillatt arenakontor-endringstyper
    companion object {
        fun fraKontorEndringsType(kontorEndringsType: KontorEndringsType): Tilordningstype {
            return when (kontorEndringsType) {
                KontorEndringsType.StartKontorSattManueltAvVeileder,
                KontorEndringsType.AutomatiskRutetTilNOE,
                KontorEndringsType.AutomatiskNorgRuting,
                KontorEndringsType.AutomatiskNorgRutingFallback,
                KontorEndringsType.AutomatiskRutingArbeidsgiverFallback,
                KontorEndringsType.AutomatiskRutetTilNavItManglerGt,
                KontorEndringsType.AutomatiskRutetTilNavItGtErLand,
                KontorEndringsType.ArenaKontorHentetSynkrontVedOppfolgingsStart,
                KontorEndringsType.AutomatiskRutetTilNavItIngenKontorFunnetForGt,
                KontorEndringsType.AutomatiskRutetTilNavItUgyldigGt,
                KontorEndringsType.ArenaKontorVedOppfolgingStartMedEtterslep,
                KontorEndringsType.ArenaKontorVedOppfolgingsStart,
                KontorEndringsType.TidligArenaKontorVedOppfolgingStart,
                KontorEndringsType.ArenaKontorFraOppfolgingsbrukerVedOppfolgingStart,
                KontorEndringsType.MIGRERING,
                KontorEndringsType.PATCH -> KONTOR_VED_OPPFOLGINGSPERIODE_START

                KontorEndringsType.FikkAddressebeskyttelse,
                KontorEndringsType.AddressebeskyttelseMistet,
                KontorEndringsType.FikkSkjerming,
                KontorEndringsType.MistetSkjerming,
                KontorEndringsType.FlyttetAvVeileder,
                KontorEndringsType.ArenaKontorManuellSynk,
                KontorEndringsType.EndretIArena -> ENDRET_KONTOR

                /* Endringer som midlertidig publiseres siden egen ruting ikke er lansert */
//                KontorEndringsType.EndretIArena,
//                KontorEndringsType.ArenaKontorHentetSynkrontVedOppfolgingsStart,
//                KontorEndringsType.ArenaKontorVedOppfolgingStartMedEtterslep,
//                KontorEndringsType.ArenaKontorVedOppfolgingsStart,
//                KontorEndringsType.TidligArenaKontorVedOppfolgingStart,
//                KontorEndringsType.ArenaKontorFraOppfolgingsbrukerVedOppfolgingStart,
//                KontorEndringsType.MIGRERING,
//                KontorEndringsType.PATCH,
                KontorEndringsType.GTKontorVedOppfolgingStart,
                KontorEndringsType.EndretBostedsadresse -> {
                    throw RuntimeException("Vi skal ikke publisere kontorendringer på kontor-endring av type $kontorEndringsType")
                }
                /* Endringer som bare skal skje på GT-kontor eller Arena-kontor */
            }
        }
    }
}

/**
 * Same as KontorTilordningMeldingDto but without AktorId and kontorNavn.
 * Needed to avoid fetching aktorId and kontorNavn in KontortilordningsProcessor
 * but still have a serializable data-transfer-object to pass it to the next processing step
 * */
@Serializable
data class OppfolgingEndretTilordningMelding(
    val kontorId: String,
    val oppfolgingsperiodeId: String,
    val ident: String,
    val kontorEndringsType: KontorEndringsType
)
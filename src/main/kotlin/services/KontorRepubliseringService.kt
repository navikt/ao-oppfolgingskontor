package services

import domain.IdenterFunnet
import domain.IdenterIkkeFunnet
import domain.IdenterOppslagFeil
import domain.IdenterResult
import kotlinx.coroutines.runBlocking
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.db.Ident.Companion.validateIdentSomKanLagres
import no.nav.db.IdentSomKanLagres
import no.nav.db.InternIdent
import no.nav.db.InvalidIdent
import no.nav.db.ValidIdent
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.IdentResult
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeOppslagResult
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import javax.sql.DataSource

class KontorRepubliseringService(
    val republiserKontor: (KontortilordningSomSkalRepubliseres) -> Result<Unit>,
    val datasource: DataSource,
    val friskOppAlleKontorNavn: suspend () -> Unit,
    val hentInternIdenterForBrukere: suspend (Ident) -> IdenterResult,
    val publiserTombstone: suspend (InternIdent) -> Result<Unit>,
    val hentOppfolgingsperiode: suspend (IdentResult) -> OppfolgingsperiodeOppslagResult,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun republiserKontorer(periodeIder: List<OppfolgingsperiodeId>) {
        if (periodeIder.isEmpty()) {
            log.info("Ingen identer oppgitt for republisering av kontorer, republiserer ikke")
            return
        }
        log.info("Skal republisere kontorer for ${periodeIder.size} identer")

        friskOppAlleKontorNavn()

        val kontorerSomSkalRepubliseres = datasource.connection.use { connection ->
            val statement = connection.prepareStatement(queryForRepublisering(periodeIder))
            val resultSet = statement.executeQuery()

            generateSequence {
                if (resultSet.next()) resultSet.toKontorSomSkalRepubliseres()
                else null
            }.toList()
        }

        log.info("Fant ${kontorerSomSkalRepubliseres.size} kontorer som skal republiseres for ${periodeIder.size} oppgitte oppfolgingsperioder")
        kontorerSomSkalRepubliseres.forEach { republiserKontor(it).getOrThrow() }
    }

    suspend fun republiserKontorer() {
        friskOppAlleKontorNavn()

        var antallPubliserte = 0
        hentAlleKontorerSomSkalRepubliseres {
            republiserKontor(it).getOrThrow()

            antallPubliserte++
            if (antallPubliserte % 500 == 0) {
                log.info("Antall publiserte: $antallPubliserte")
            }
        }
        log.info("Totalt antall publiserte: $antallPubliserte")
    }

    fun hentAlleKontorerSomSkalRepubliseres(
        publiserEndringPaaKafka: (KontortilordningSomSkalRepubliseres) -> Unit
    ): Result<Unit> = runCatching {
        // Use streaming / cursor mode
        val conn = datasource.connection
        conn.autoCommit = false
        val statement = conn.prepareStatement(queryForRepublisering())
        statement.fetchSize = 500

        val resultSet = statement.executeQuery()
        while (resultSet.next()) {
            publiserEndringPaaKafka(resultSet.toKontorSomSkalRepubliseres())
        }
        resultSet.close()
        statement.close()
    }.onFailure {
        log.error("Republisering av kontor feilet", it)
    }

    suspend fun republiserTombstone(identer: List<String>) {
        log.info("Skal republisere tombstone på ${identer.size} indenter")
        identer.toSet()
            .asSequence()
            .map {
                val result = Ident.validate(it, Ident.HistoriskStatus.UKJENT)
                if (result is InvalidIdent) {
                    log.info("Fikk ugyldig ident: ${result.message}, hopper over publisering av tombstone på gitt ident")
                }
                result
            }
            .filterIsInstance<ValidIdent>()
            .map { runBlocking { hentInternIdenterForBrukere(it.ident) } }
            .mapNotNull {
                when (it) {
                    is IdenterFunnet -> it
                    is IdenterIkkeFunnet -> {
                        log.error("Fant ikke internIdent: ${it.message}")
                        null
                    }
                    is IdenterOppslagFeil -> {
                        log.error("Feiled å hente internIdent: ${it.message}")
                        null
                    }
                }
            }
            .filter {
                val periodeResult = runBlocking { hentOppfolgingsperiode(it.finnForetrukketIdent()) }
                when (periodeResult) {
                    is AktivOppfolgingsperiode -> false // IKKE publiser tombstone på brukere som er aktive
                    NotUnderOppfolging -> true
                    is OppfolgingperiodeOppslagFeil -> {
                        log.error("Klarte ikke gjøre oppslag på oppfølgingsperioden til bruker: ${periodeResult.message}")
                        false
                    }
                }
            }
            .forEach {
                publiserTombstone(it.internIdent)
                    .onFailure { log.error("Republisering av kontor feilet", it) }
            }
    }

    fun queryForRepublisering(oppfolgingsperiodeIder: List<OppfolgingsperiodeId> = emptyList()): String {
        val oppfolgingsperiodeIder = oppfolgingsperiodeIder.joinToString(",") { "'${it.value}'" }
        @Language("PostgreSQL")
        val query = """
            select distinct on (oppfolgingsperiode.oppfolgingsperiode_id)
                arbeidsoppfolgingskontor.fnr,
                arbeidsoppfolgingskontor.kontor_id,
                arbeidsoppfolgingskontor.updated_at,
                aktorId.ident as aktorId, -- aktørid
                oppfolgingsperiode.oppfolgingsperiode_id,
                historikk.kontorendringstype,
                kontornavn.kontor_navn,
                alle_identer.intern_ident,
                CASE
                    WHEN alle_identer.ident_type = 'FNR' and alle_identer.historisk = false THEN 1
                    WHEN alle_identer.ident_type = 'DNR' and alle_identer.historisk = false THEN 2
                    WHEN alle_identer.ident_type = 'NPID' and alle_identer.historisk = false THEN 3
                    WHEN alle_identer.ident_type = 'FNR' and alle_identer.historisk = true THEN 4
                    WHEN alle_identer.ident_type = 'DNR' and alle_identer.historisk = true THEN 5
                    WHEN alle_identer.ident_type = 'NPID' and alle_identer.historisk = true THEN 6
                    ELSE 7
                END as ident_prio
            from oppfolgingsperiode
                join ident_mapping input_ident on oppfolgingsperiode.fnr = input_ident.ident
                join ident_mapping alle_identer 
                    on input_ident.intern_ident = alle_identer.intern_ident 
                    and alle_identer.ident_type != 'AKTOR_ID'
                join ident_mapping aktorId on input_ident.intern_ident = aktorId.intern_ident and aktorId.ident_type = 'AKTOR_ID'
                join arbeidsoppfolgingskontor on alle_identer.ident = arbeidsoppfolgingskontor.fnr
                join kontorhistorikk historikk on arbeidsoppfolgingskontor.historikk_entry = historikk.id
                join kontornavn on arbeidsoppfolgingskontor.kontor_id = kontornavn.kontor_id
            where aktorId.historisk = false
                ${ 
                    if (oppfolgingsperiodeIder.isEmpty()) "" 
                    else " and oppfolgingsperiode.oppfolgingsperiode_id in (${oppfolgingsperiodeIder})" 
                }
            order by oppfolgingsperiode.oppfolgingsperiode_id, ident_prio
        """.trimIndent()
        return query
    }
}

fun ResultSet.toKontorSomSkalRepubliseres(): KontortilordningSomSkalRepubliseres {
    val ident = validateIdentSomKanLagres(this.getString("fnr"), Ident.HistoriskStatus.UKJENT)
    val aktorId = AktorId(this.getString("aktorId"), Ident.HistoriskStatus.UKJENT)
    val kontorId = KontorId(this.getString("kontor_id"))
    val updatedAt = this.getTimestamp("updated_at").toInstant().atZone(ZoneId.systemDefault())
    val oppfolgingsperiodeId = OppfolgingsperiodeId(this.getObject("oppfolgingsperiode_id", UUID::class.java))
    val kontorEndringsType = KontorEndringsType.valueOf(this.getString("kontorendringstype"))
    val kontorNavn = KontorNavn(this.getString("kontor_navn"))
    val internIdent = InternIdent(this.getLong("intern_ident"))
    return KontortilordningSomSkalRepubliseres(
        ident = ident,
        internIdent = internIdent,
        aktorId = aktorId,
        kontorId = kontorId,
        kontorNavn = kontorNavn,
        updatedAt = updatedAt,
        oppfolgingsperiodeId = oppfolgingsperiodeId,
        kontorEndringsType = kontorEndringsType,
    )
}

data class KontortilordningSomSkalRepubliseres(
    val ident: IdentSomKanLagres,
    val internIdent: InternIdent,
    val aktorId: AktorId,
    val kontorId: KontorId,
    val kontorNavn: KontorNavn,
    val updatedAt: ZonedDateTime,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
    val kontorEndringsType: KontorEndringsType,
)

package services

import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.db.Ident.Companion.validateIdentSomKanLagres
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.OppfolgingsperiodeId
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import javax.sql.DataSource

class KontorRepubliseringService(
    val republiserKontor: (KontorSomSkalRepubliseres) -> Unit,
    val datasource: DataSource,
    val friskOppAlleKontorNavn: suspend () -> Unit,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun republiserKontorer() {
        friskOppAlleKontorNavn()

        var antallPubliserte = 0
        hentAlleKontorerSomSkalRepubliseres {
            republiserKontor(it)

            antallPubliserte++
            if (antallPubliserte % 5000 == 0) {
                log.info("Antall publiserte: $antallPubliserte")
            }
        }
        log.info("Totalt antall publiserte: $antallPubliserte")
    }

    fun hentAlleKontorerSomSkalRepubliseres(
        publiserEndringPaaKafka: (KontorSomSkalRepubliseres) -> Unit
    ): Result<Unit> = runCatching {
        val query = """
            select
                arbeidsoppfolgingskontor.fnr,
                arbeidsoppfolgingskontor.kontor_id,
                arbeidsoppfolgingskontor.updated_at,
                aktorId.ident as aktorId, -- aktørid
                oppfolgingsperiode.oppfolgingsperiode_id,
                historikk.kontorendringstype,
                kontornavn.kontor_navn
            from oppfolgingsperiode
                join ident_mapping input_ident on oppfolgingsperiode.fnr = input_ident.ident
                join ident_mapping alle_identer on input_ident.intern_ident = alle_identer.intern_ident and alle_identer.ident_type != 'AKTOR_ID'
                join ident_mapping aktorId on input_ident.intern_ident = aktorId.intern_ident and aktorId.ident_type = 'AKTOR_ID'
                join arbeidsoppfolgingskontor on alle_identer.ident = arbeidsoppfolgingskontor.fnr
                join kontorhistorikk historikk on arbeidsoppfolgingskontor.historikk_entry = historikk.id
                join kontornavn on arbeidsoppfolgingskontor.kontor_id = kontornavn.kontor_id
            where alle_identer.historisk = false and aktorId.historisk = false
        """.trimIndent()

        // Use streaming / cursor mode
        val conn = datasource.connection
        conn.autoCommit = false
        val statement = conn.prepareStatement(query)
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
}

fun ResultSet.toKontorSomSkalRepubliseres(): KontorSomSkalRepubliseres {
    val ident = validateIdentSomKanLagres(this.getString("fnr"), Ident.HistoriskStatus.UKJENT)
    val aktorId = AktorId(this.getString("aktorId"), Ident.HistoriskStatus.UKJENT)
    val kontorId = KontorId(this.getString("kontor_id"))
    val updatedAt = this.getTimestamp("updated_at").toInstant().atZone(ZoneId.systemDefault())
    val oppfolgingsperiodeId = OppfolgingsperiodeId(this.getObject("oppfolgingsperiode_id", UUID::class.java))
    val kontorEndringsType = KontorEndringsType.valueOf(this.getString("kontorendringstype"))
    val kontorNavn = KontorNavn(this.getString("kontor_navn"))
    return KontorSomSkalRepubliseres(
        ident = ident,
        aktorId = aktorId,
        kontorId = kontorId,
        kontorNavn = kontorNavn,
        updatedAt = updatedAt,
        oppfolgingsperiodeId = oppfolgingsperiodeId,
        kontorEndringsType = kontorEndringsType,
    )
}

data class KontorSomSkalRepubliseres(
    val ident: IdentSomKanLagres,
    val aktorId: AktorId,
    val kontorId: KontorId,
    val kontorNavn: KontorNavn,
    val updatedAt: ZonedDateTime,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
    val kontorEndringsType: KontorEndringsType,
)

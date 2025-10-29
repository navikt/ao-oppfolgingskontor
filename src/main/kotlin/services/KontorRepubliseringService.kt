package services

import db.table.IdentMappingTable
import io.ktor.server.util.toZonedDateTime
import kafka.producers.KontorEndringProducer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.db.Ident.Companion.validateIdentSomKanLagres
import no.nav.db.IdentSomKanLagres
import no.nav.db.PostgresDataSource
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime
import java.util.UUID
import javax.sql.DataSource


val logger = LoggerFactory.getLogger("Application.KontorRepubliseringService")

class KontorRepubliseringService(
    val kafkaProducer: KontorEndringProducer,
    val datasource: DataSource,
) {

    suspend fun republiserKontorer(): Unit = withContext(Dispatchers.IO) {
        var antallPubliserte = 0;
        hentAlleKontorerSomSkalRepubliseres {
            kafkaProducer.republiserKontor(it)

            antallPubliserte++
            if (antallPubliserte % 5000 == 0) {
                logger.info("Antall publiserte: $antallPubliserte")
            }
        }
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
                historikk.kontorendringstype
            from oppfolgingsperiode
                join ident_mapping input_ident on oppfolgingsperiode.fnr = input_ident
                join ident_mapping alle_identer on input_ident.intern_ident = alle_identer.intern_ident and != 'AKTOR_ID'
                join ident_mapping aktorId on input_ident.intern_ident = alle_identer.intern_ident and ident_type = 'AKTOR_ID'
                join arbeidsoppfolgingskontor on alle_identer.ident = arbeidsoppfolgingskontor.fnr
                join public.kontorhistorikk historikk on arbeidsoppfolgingskontor.historikk_entry = historikk.id
            where alle_identer.historisk = false and aktorId.historisk = false
        """.trimIndent()

        // Use streaming / cursor mode
        val conn = datasource.connection
        conn.autoCommit = false
        val statement = conn.createStatement()
        statement.fetchSize = 500

        val resultSet = statement.executeQuery(query)
        while (resultSet.next()) {
            val ident = validateIdentSomKanLagres(resultSet.getString("fnr"), Ident.HistoriskStatus.UKJENT)
            val aktorId = AktorId(resultSet.getString("aktorId"), Ident.HistoriskStatus.UKJENT)
            val kontorId = KontorId(resultSet.getString("kontor_id"))
            val updatedAt = resultSet.getObject("updated_at", ZonedDateTime::class.java)
            val oppfolgingsperiodeId = OppfolgingsperiodeId(resultSet.getObject("oppfolgingsperiode_id", UUID::class.java))
            val kontorEndringsType = KontorEndringsType.valueOf(resultSet.getString("kontorendringstype"))
            publiserEndringPaaKafka(
                KontorSomSkalRepubliseres(
                    ident = ident,
                    aktorId = aktorId,
                    kontorId = kontorId,
                    updatedAt = updatedAt,
                    oppfolgingsperiodeId = oppfolgingsperiodeId,
                    kontorEndringsType = kontorEndringsType,
                )
            )
        }
        resultSet.close()
        statement.close()
    }
}

data class KontorSomSkalRepubliseres(
    val ident: IdentSomKanLagres,
    val aktorId: AktorId,
    val kontorId: KontorId,
    val updatedAt: ZonedDateTime,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
    val kontorEndringsType: KontorEndringsType,
)

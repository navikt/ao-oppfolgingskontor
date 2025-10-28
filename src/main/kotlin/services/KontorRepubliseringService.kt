package services

import db.table.IdentMappingTable
import kafka.producers.KontorEndringProducer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.db.Ident
import no.nav.db.Ident.Companion.validateOrThrow
import no.nav.db.IdentSomKanLagres
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime


val logger = LoggerFactory.getLogger("Application.KontorRepubliseringService")

class KontorRepubliseringService(
    val kafkaProducer: KontorEndringProducer
) {

    suspend fun republiserKontorer(): Unit = withContext(Dispatchers.IO) {
        val oppfolgingsperioder = transaction {
            OppfolgingsperiodeEntity.all().toList()
        }
        logger.info("Antall oppfølgingsperioder: ${oppfolgingsperioder.size}")

        val kontorerSomSkalRepubliseres = hentAlleKontorerSomSkalRepubliseres(oppfolgingsperioder)
        logger.info("Antall kontorer som skal republiseres: ${kontorerSomSkalRepubliseres.size}")

        var antallPubliserte = 0;
        kontorerSomSkalRepubliseres.forEach { kontorSomSkalRepubliseres ->
            kafkaProducer.republiserKontor(kontorSomSkalRepubliseres)

            antallPubliserte++

            if (antallPubliserte % 5000 == 0) {
                logger.info("Antall publiserte: $antallPubliserte")
            }
        }
    }

    fun hentAlleKontorerSomSkalRepubliseres(
        oppfolgingsperioder: List<OppfolgingsperiodeEntity>
    ): List<KontorSomSkalRepubliseres> = transaction {
        val alleIdenter = IdentMappingTable.alias("alleIdenter")

        val kontorRows = IdentMappingTable
            .join(
                alleIdenter,
                JoinType.INNER,
                onColumn = IdentMappingTable.internIdent,
                otherColumn = alleIdenter[IdentMappingTable.internIdent]
            )
            .join(
                ArbeidsOppfolgingKontorTable,
                JoinType.INNER,
                onColumn = alleIdenter[IdentMappingTable.id],
                otherColumn = ArbeidsOppfolgingKontorTable.id
            )
            .select(
                ArbeidsOppfolgingKontorTable.id,
                ArbeidsOppfolgingKontorTable.kontorId,
                ArbeidsOppfolgingKontorTable.updatedAt
            )
            .where { IdentMappingTable.id inList oppfolgingsperioder.map { it.fnr } }
            .orderBy(ArbeidsOppfolgingKontorTable.updatedAt, SortOrder.DESC)
            .map { row ->
                row[ArbeidsOppfolgingKontorTable.id] to row
            }
            .groupBy({ it.first }) { it.second }
            .mapValues { (_, rows) -> rows.first() }

        oppfolgingsperioder.mapNotNull { periode ->
            kontorRows[periode.fnr]?.let { row ->
                KontorSomSkalRepubliseres(
                    ident = validateOrThrow(row[ArbeidsOppfolgingKontorTable.id].value, Ident.HistoriskStatus.UKJENT),
                    kontorId = KontorId(row[ArbeidsOppfolgingKontorTable.kontorId]),
                    updatedAt = row[ArbeidsOppfolgingKontorTable.updatedAt].toZonedDateTime(),
                    oppfolgingsperiodeId = OppfolgingsperiodeId(periode.oppfolgingsperiodeId)
                )
            }
        }
    }
}

data class KontorSomSkalRepubliseres(
    val ident: Ident,
    val kontorId: KontorId,
    val updatedAt: ZonedDateTime,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
    val kontorEndringsType: KontorEndringsType,
)

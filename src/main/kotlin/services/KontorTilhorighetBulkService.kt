package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.identType
import db.table.IdentMappingTable.internIdent
import db.table.IdentMappingTable.slettetHosOss
import no.nav.db.Ident
import no.nav.db.finnForetrukketIdentRelaxed
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.collections.map

object KontorTilhorighetBulkService {
    val logger = LoggerFactory.getLogger(KontorTilhorighetBulkService::class.java)

    fun getKontorTilhorighetBulk(identer: List<Ident>): List<KontorBulkDto> {

        val kontorerPaIdentMutable = mutableMapOf<String, Set<KontorBulkResultat>>()

        transaction {
            val alleIdenter = IdentMappingTable.alias("alleIdenter")
            IdentMappingTable
                .join(
                    alleIdenter,
                    JoinType.INNER,
                    onColumn = internIdent,
                    otherColumn = alleIdenter[internIdent]
                )
                .join(
                    ArbeidsOppfolgingKontorTable,
                    JoinType.INNER,
                    onColumn = alleIdenter[IdentMappingTable.id],
                    otherColumn = ArbeidsOppfolgingKontorTable.id,
                )
                .select(IdentMappingTable.id, alleIdenter[IdentMappingTable.id], ArbeidsOppfolgingKontorTable.kontorId)
                .where {
                    (IdentMappingTable.id inList identer.map { it.value }) and
                        (alleIdenter[slettetHosOss].isNull()) and
                        (alleIdenter[identType] neq "AKTOR_ID")
                }
                .map { row ->
                    // Harvest the exposed fruits
                    val innkommendeIdent = row[IdentMappingTable.id].value
                    val kontor = KontorBulkResultat(
                        row[alleIdenter[IdentMappingTable.id]].value,
                        row[ArbeidsOppfolgingKontorTable.kontorId],
                    )
                    kontorerPaIdentMutable.merge(innkommendeIdent, setOf(kontor)) { oldValue, newValue ->
                        oldValue.plus(newValue)
                    }
                }
        }

        return identer.map { inputIdent ->
            val kontorer = kontorerPaIdentMutable[inputIdent.value]
            when {
                kontorer == null -> KontorBulkDto(inputIdent.value, null)
                kontorer.size == 1 -> KontorBulkDto(inputIdent.value, kontorer.first().kontorId)
                else -> {
                    logger.warn("Fant flere kontor på samme person (med forskjellige identer)")
                    finnForetrukketKontor(kontorer, inputIdent.value)
                }
            }
        }
    }

    private fun finnForetrukketKontor(kontorer: Set<KontorBulkResultat>, inputIdent: String): KontorBulkDto {
        val foretrukketIdent = kontorer.map {
            Ident.of(it.lagretPaIdent, Ident.HistoriskStatus.UKJENT)
        }.finnForetrukketIdentRelaxed()
            ?: return KontorBulkDto(inputIdent, null)
        val foretrukketKontor = kontorer.find { it.lagretPaIdent == foretrukketIdent.value }
            ?: return KontorBulkDto(inputIdent, null)
        return KontorBulkDto(inputIdent, foretrukketKontor.kontorId)
    }
}

data class KontorBulkResultat(
    val lagretPaIdent: String,
    val kontorId: String,
)

data class KontorBulkDto(
    val ident: String,
    val kontorId: String?,
)
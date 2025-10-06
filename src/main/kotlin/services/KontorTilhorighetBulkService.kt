package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.id
import db.table.IdentMappingTable.identType
import db.table.IdentMappingTable.internIdent
import no.nav.db.Ident
import no.nav.db.finnForetrukketIdentRelaxed
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.http.client.IdenterResult
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import java.sql.ResultSet
import kotlin.collections.map

class KontorTilhorighetBulkService(
) {
    fun leggTilResultat(result : ResultSet, identKontorer : MutableMap<String, Set<KontorBulkResultat>>) {
        val innkommendeIdent = result.getString(1)
        val lagretPaIdent = result.getString(2)
        val kontorId = result.getString(3)
        identKontorer.merge(innkommendeIdent,
            setOf(KontorBulkResultat(lagretPaIdent, kontorId))
        ) { oldValue, value -> oldValue.plus(value) }
    }

    fun List<Ident>.toSqlQueryParam(): String {
        return this.map { it.value }.joinToString { "," }
    }

    suspend fun getKontorTilhorighetBulk(identer: List<Ident>): List<KontorBulkDto?> {

        /*
        TODO: Vurder om det er bedre å bruke raw-sql
        @Language("PostgreSQL")
        val query = """
            SELECT innkommendeIdent.ident, alleIdenter.ident, ao.kontor_id
            FROM ident_mapping innkommendeIdent
            JOIN ident_mapping alleIdenter on innkommendeIdent.intern_ident = alleIdenter.intern_ident
            JOIN arbeidsoppfolgingskontor ao on alleIdenter.ident = ao.fnr
            WHERE innkommendeIdent.slettet_hos_oss IS NULL 
                AND innkommendeIdent.ident in (?);
        """.trimIndent()*/

        val alleIdenter = IdentMappingTable.alias("alleIdenter")
        val kontorerPaIdentMutable = mutableMapOf<String, Set<KontorBulkResultat>>()
        IdentMappingTable
            .join (
                alleIdenter,
                JoinType.INNER,
                onColumn = internIdent,
                otherColumn = alleIdenter[internIdent]
            )
            .join(
                ArbeidsOppfolgingKontorTable,
                JoinType.INNER,
                onColumn = alleIdenter[id],
                otherColumn = ArbeidsOppfolgingKontorTable.id,
            )
            .select(id, alleIdenter[id], ArbeidsOppfolgingKontorTable.kontorId)
            .where { (id inList identer.map { it.value }) and (IdentMappingTable.slettetHosOss.isNull()) }
            .map { row ->
                // Harvest the exposed fruits
                val innkommendeIdent = row[id].value
                val kontor = KontorBulkResultat(
                    row[alleIdenter[id]].value,
                    row[identType],
                )
                kontorerPaIdentMutable.merge(innkommendeIdent, setOf(kontor)) { oldValue, newValue ->
                    oldValue.plus(newValue)
                }
            }

//        val identerTilKontorer: Map<String, Set<KontorBulkResultat>> = transaction {
//            exec(
//                stmt = query,
//                args = listOf(VarCharColumnType() to identer.toSqlQueryParam())
//            ) { result ->
//                val identKontorer = mutableMapOf<String, Set<KontorBulkResultat>>()
//                while (result.next()) {
//                    leggTilResultat(result, identKontorer)
//                }
//                identKontorer
//            }
//        } ?: emptyMap()

        return identer.map { inputIdent ->
            val kontorer = kontorerPaIdentMutable[inputIdent.value]
            when {
                kontorer == null -> null
                kontorer.size == 1 -> KontorBulkDto(inputIdent.value, kontorer.first().kontorId)
                else -> finnForetrukketKontor(kontorer, inputIdent.value)
            }
        }
    }

    fun finnForetrukketKontor(kontorer: Set<KontorBulkResultat>, inputIdent: String): KontorBulkDto? {
        val foretrukketIdent = kontorer.map {
            Ident.of(it.lagretPaIdent, Ident.HistoriskStatus.UKJENT)
        }.finnForetrukketIdentRelaxed()
            ?: return null
        val foretrukketKontor = kontorer.find { it.lagretPaIdent == foretrukketIdent.value }
            ?: return null
        return KontorBulkDto(inputIdent, foretrukketKontor.kontorId)
    }
}

data class KontorBulkResultat(
    val lagretPaIdent: String,
    val kontorId: String,
)

data class KontorBulkDto(
    val ident: String,
    val kontorId: String,
)
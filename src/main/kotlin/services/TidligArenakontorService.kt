package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.internIdent
import db.table.TidligArenaKontorTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import no.nav.db.Ident
import no.nav.domain.KontorId
import no.nav.domain.externalEvents.TidligArenaKontor
import no.nav.kafka.consumers.SkalKanskjeUnderOppfolging
import org.jetbrains.exposed.sql.JoinType.INNER
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes

class TidligArenakontorService {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun lagreTidligArenaKontor(skalKanskjeUnderOppfolging: SkalKanskjeUnderOppfolging) {
//        transaction {
//            TidligArenaKontorTable.upsert {
//                it[id] = skalKanskjeUnderOppfolging.ident.value
//                it[kontorId] = skalKanskjeUnderOppfolging.kontorId.id
//                it[sisteEndretDato] = skalKanskjeUnderOppfolging.sistEndretDatoArena
//                it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
//            }
//        }
    }

    suspend fun slettTidligArenakontor() = supervisorScope {
        delay(2.minutes)

        while (true) {
            logger.info("Starter periodisk sletting av tidlig arenakontor")

            delay(5.minutes)
        }
    }

    fun hentArenaKontorOgSlettHvisFunnet(ident: Ident): TidligArenaKontor? {
//        return transaction {
//            val alleIdenter = IdentMappingTable.alias("alleIdenter")
//            val tidligArenaKontorOgIdent = IdentMappingTable
//                .join(alleIdenter, INNER, onColumn = internIdent, otherColumn = alleIdenter[internIdent])
//                .join(
//                    TidligArenaKontorTable,
//                    INNER,
//                    onColumn = alleIdenter[IdentMappingTable.id],
//                    otherColumn = TidligArenaKontorTable.id
//                )
//                .select(
//                    TidligArenaKontorTable.id,
//                    TidligArenaKontorTable.kontorId,
//                    TidligArenaKontorTable.sisteEndretDato
//                )
//                .where { IdentMappingTable.id eq ident.value }
//                .map { row ->
//                    TidligArenaKontor(
//                        kontor = KontorId(row[TidligArenaKontorTable.kontorId]),
//                        sistEndretDato = row[TidligArenaKontorTable.sisteEndretDato]
//                    ) to row[TidligArenaKontorTable.id]
//                }.firstOrNull()
//
//            val identSomKontorErLagretPå = tidligArenaKontorOgIdent?.second
//            val tidligArenaKontor = tidligArenaKontorOgIdent?.first
//
//            identSomKontorErLagretPå?.let {
//                TidligArenaKontorTable.deleteWhere { TidligArenaKontorTable.id eq identSomKontorErLagretPå.value }
//            }
//
//            tidligArenaKontor
//        }
        return null
    }
}
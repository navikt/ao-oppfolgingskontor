package services

import db.table.TidligArenaKontorTable
import no.nav.kafka.consumers.SkalKanskjeUnderOppfolging
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.ZonedDateTime

class TidligArenakontorService {
    fun lagreTidligArenaKontor(skalKanskjeUnderOppfolging: SkalKanskjeUnderOppfolging) {
        transaction {
            TidligArenaKontorTable.upsert {
                it[id] = skalKanskjeUnderOppfolging.ident.value
                it[kontorId] = skalKanskjeUnderOppfolging.kontorId.id
                it[sisteEndretDato] = skalKanskjeUnderOppfolging.sistEndretDatoArena
                it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
            }
        }
    }

    fun slettKontor() {

    }
}
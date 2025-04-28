package no.nav.db

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import no.nav.db.dto.ArbeidsoppfolgingKontor
import no.nav.db.dto.ArenaKontor
import no.nav.db.dto.toArbeidsoppfolgingKontor
import no.nav.db.dto.toArenaKontor
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

class KontorRepo(val dataSource: DataSource) {
    init {
        Database.connect(dataSource)
    }

    suspend fun getKontor(fnr: Fnr): KontorTilhorighet {
        val (arenaKontor, arbeidsoppfolgingKontor) = coroutineScope {
            val arenaKontor = async { ArenaKontorEntity.findById(fnr) }
            val arbeidsoppfolgingKontor = async { ArbeidsOppfolgingKontorEntity.findById(fnr) }
            Pair(arenaKontor.await(), arbeidsoppfolgingKontor.await())
        }
        return KontorTilhorighet(
            fnr,
            arenaKontor = arenaKontor?.toArenaKontor(),
            arbeidsoppfolgingKontor = arbeidsoppfolgingKontor?.toArbeidsoppfolgingKontor()
        )
    }
}

data class KontorTilhorighet(
    val fnr: Fnr,
    val arenaKontor: ArenaKontor?,
    val arbeidsoppfolgingKontor: ArbeidsoppfolgingKontor?,
)


package no.nav.db.dto

import no.nav.db.entity.ArenaKontorEntity

class ArenaKontor (
    kontorId: String,
    metadata: KontorMetadata,
): Kontor(kontorId, metadata)

fun ArenaKontorEntity.toArenaKontor(): ArenaKontor {
    return ArenaKontor(
        kontorId = this.kontorId,
        metadata = KontorMetadata(
            createdAt = this.createdAt,
            updatedAt = this.updatedAt,
            endretAv = this.endretAv,
            endretAvType = this.endretAvType
        )
    )
}

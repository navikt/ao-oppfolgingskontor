package no.nav.db.dto

import no.nav.db.entity.ArenaKontorEntity

class ArenaDBKontor (
    kontorId: String,
    metadata: KontorMetadata,
): DBKontor(kontorId, metadata)

fun ArenaKontorEntity.toArenaKontor(): ArenaDBKontor {
    return ArenaDBKontor(
        kontorId = this.kontorId,
        metadata = KontorMetadata(
            createdAt = this.createdAt,
            updatedAt = this.updatedAt
        )
    )
}

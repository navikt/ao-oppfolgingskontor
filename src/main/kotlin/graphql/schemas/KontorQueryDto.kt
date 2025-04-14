package no.nav.graphql.schemas

import kotlinx.serialization.Serializable

@Serializable
data class KontorQueryDto(
    val kontorId: String,
    val kilde: String,
)

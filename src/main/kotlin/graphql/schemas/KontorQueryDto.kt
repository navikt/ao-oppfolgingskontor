package no.nav.graphql.schemas

data class KontorQueryDto(
    val kontorId: String,
    val kontorNavn: String,
    val kontorType: String,
)
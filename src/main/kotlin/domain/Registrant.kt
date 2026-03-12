package no.nav.domain

import domain.Systemnavn
import no.nav.db.IdentSomKanLagres

sealed class Registrant() {
    abstract fun getIdent(): String
    abstract fun getType(): String
}

data class System(val systemnavn: Systemnavn) : Registrant() {
    override fun getIdent() = systemnavn.name
    override fun getType() = "SYSTEM"
}

data class Veileder(val navIdent: NavIdent) : Registrant() {
    override fun getIdent() = navIdent.id
    override fun getType() = "VEILEDER"
}

data class Bruker(val ident: IdentSomKanLagres) : Registrant() {
    override fun getIdent() = ident.value
    override fun getType() = "BRUKER"
}

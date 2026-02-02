package no.nav.domain

import no.nav.db.IdentSomKanLagres

sealed class Registrant() {
    abstract fun getIdent(): String
    abstract fun getType(): String
}

class System() : Registrant() {
    override fun getIdent() = "SYSTEM"
    override fun getType() = "SYSTEM"
}

class Veileder(val navIdent: NavIdent) : Registrant() {
    override fun getIdent() = navIdent.id
    override fun getType() = "VEILEDER"
}

class Bruker(val ident: IdentSomKanLagres) : Registrant() {
    override fun getIdent() = ident.value
    override fun getType() = "BRUKER"
}

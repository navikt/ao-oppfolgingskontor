package no.nav.domain

import domain.Systemnavn
import no.nav.db.IdentSomKanLagres

sealed class Registrant() {
    abstract fun getIdent(): String
    abstract fun getType(): String
}

class System(val systemnavn: Systemnavn) : Registrant() {
    override fun getIdent() = systemnavn.name
    override fun getType() = "SYSTEM"
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as System
        return systemnavn == other.systemnavn
    }

    override fun hashCode(): Int {
        return systemnavn.hashCode()
    }
}

data class Veileder(val navIdent: NavIdent) : Registrant() {
    override fun getIdent() = navIdent.id
    override fun getType() = "VEILEDER"
}

data class Bruker(val ident: IdentSomKanLagres) : Registrant() {
    override fun getIdent() = ident.value
    override fun getType() = "BRUKER"
}

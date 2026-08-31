package ru.ruznak.netscan.update

internal data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    companion object {
        private val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

        fun parse(value: String): SemanticVersion? = pattern.matchEntire(value.trim())?.destructured?.let { (a, b, c) ->
            SemanticVersion(a.toInt(), b.toInt(), c.toInt())
        }
    }
}

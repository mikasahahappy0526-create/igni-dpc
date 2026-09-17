package app.igni.dpc.update

/**
 * Minimal SemVer (major.minor.patch[+/-suffix ignored]) for release tag comparison.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        return compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?""")

        /** Parse `1.0.6`, `v1.0.6`, or tag-like strings. Returns null if unparsable. */
        fun parse(raw: String?): SemVer? {
            if (raw.isNullOrBlank()) return null
            val m = PATTERN.find(raw.trim()) ?: return null
            return SemVer(
                major = m.groupValues[1].toIntOrNull() ?: return null,
                minor = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0,
                patch = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            )
        }
    }
}

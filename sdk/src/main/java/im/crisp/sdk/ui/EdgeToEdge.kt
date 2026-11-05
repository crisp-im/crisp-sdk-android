package im.crisp.sdk.ui

data class EdgeToEdge(
    val top: Boolean = false,
    val bottom: Boolean = false,
) {
    companion object {
        val NONE = EdgeToEdge(top = false, bottom = false);
        val TOP = EdgeToEdge(top = true, bottom = false);
        val BOTTOM = EdgeToEdge(top = false, bottom = true);
        val ALL = EdgeToEdge(top = true, bottom = true);

        internal const val NONE_ATTR_VALUE = 0
        internal const val TOP_ATTR_VALUE = 1
        internal const val BOTTOM_ATTR_VALUE = 2
        internal const val ALL_ATTR_VALUE = 3

        internal fun fromFlags(flags: Int): EdgeToEdge = EdgeToEdge(
            top = (flags and TOP_ATTR_VALUE) != 0,
            bottom = (flags and BOTTOM_ATTR_VALUE) != 0
        )
    }

    internal fun toFlags(): Int = (if (top) TOP_ATTR_VALUE else 0) or (if (bottom) BOTTOM_ATTR_VALUE else 0)
}
package android.content.res

/** Plugins built with requiresResources get this; Android resource tables are not decoded on Linux. */
open class Resources {
    open fun getIdentifier(name: String, defType: String?, defPackage: String?): Int = 0
    open fun getString(id: Int): String = ""
    open fun getText(id: Int): CharSequence = getString(id)

    companion object {
        @JvmField val EMPTY = Resources()
    }
}

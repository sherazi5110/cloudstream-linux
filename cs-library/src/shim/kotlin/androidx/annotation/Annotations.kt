// Source-compatible stand-ins for the androidx.annotation markers used by the
// upstream library (the real artifact lives on Google Maven only).
package androidx.annotation

@Retention(AnnotationRetention.BINARY)
annotation class AnyThread

@Retention(AnnotationRetention.BINARY)
annotation class MainThread

@Retention(AnnotationRetention.BINARY)
annotation class WorkerThread

@Retention(AnnotationRetention.BINARY)
annotation class VisibleForTesting(val otherwise: Int = PRIVATE) {
    companion object {
        const val PRIVATE = 2
        const val PACKAGE_PRIVATE = 3
        const val PROTECTED = 4
        const val NONE = 5
    }
}

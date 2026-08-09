# The emulator core is plain Kotlin with no reflection in it, so R8 is free to do as it likes.
# Nothing here is needed for correctness; these rules only keep the build quiet and the traces
# readable.

-dontwarn org.jetbrains.annotations.**

# Keep line numbers so that a crash report from a release build is worth reading.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

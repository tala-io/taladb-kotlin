# Applied to every app that depends on TalaDB.

# JNI binds by name: dev.taladb.Native's native methods are looked up as
# Java_dev_taladb_Native_<name>, so neither the class nor the methods may be
# renamed or removed.
-keepclasseswithmembernames,includedescriptorclasses class dev.taladb.Native {
    native <methods>;
}
-keep class dev.taladb.Native { *; }

# The shim finds the exception class by name in JNI_OnLoad and constructs it
# with (message, code). A renamed class or a stripped constructor fails the
# library load outright.
-keep class dev.taladb.TalaDBException {
    <init>(java.lang.String, java.lang.String);
}

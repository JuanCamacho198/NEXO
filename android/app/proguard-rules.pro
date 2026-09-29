# Room runtime/generated wiring
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# App navigation routes used by Compose NavHost
-keep class com.nextpage.presentation.navigation.NextPageDestination { *; }
-keep class com.nextpage.presentation.navigation.NextPageDestination$* { *; }

# Google Drive API client — uses reflection for REST serialization
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }

# Gson models used by Google Drive Sync
-keep class com.nextpage.data.remote.sync.BookStateJson { *; }
-keep class com.nextpage.data.remote.sync.ProgressStateJson { *; }
-keep class com.nextpage.data.remote.sync.HighlightStateJson { *; }
-keep class com.nextpage.data.remote.sync.BookmarkStateJson { *; }

# Optional SLF4J backend not packaged on Android
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Security: strip debug logs from release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# R8 missing-class warnings for Apache HttpComponents' optional JNDI/GSSAPI
# integrations (javax.naming.*, org.ietf.jgss.*). Copied verbatim from AGP's
# generated app/build/outputs/mapping/release/missing_rules.txt — these classes
# are not on the Android classpath, and the referenced code paths are unused.
-dontwarn javax.naming.InvalidNameException
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid

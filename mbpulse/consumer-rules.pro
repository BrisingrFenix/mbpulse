# Rules applied to host apps that build with R8/minification.

# HealthConnectBackgroundImport reads each changed record's time through its
# public getters by name (Health Connect's record-time interfaces are
# Kotlin-internal). Keep them so background re-reads stay narrow; without them
# the import still works, but re-reads everything since the last run.
-keepclassmembers class androidx.health.connect.client.records.** {
    public java.time.Instant getStartTime();
    public java.time.Instant getEndTime();
    public java.time.Instant getTime();
}

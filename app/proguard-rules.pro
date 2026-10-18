# WorkManager, Compose and AndroidX ship their own consumer rules (workers are kept by class name).

# Failure names are persisted (WorkManager output data, SharedPreferences) and must stay stable
# across versions, so enum names must not be obfuscated.
-keepclassmembers enum io.github.willianchavez.winali.Failure { *; }
-keepnames enum io.github.willianchavez.winali.Failure

# Receiver and workers are named in the manifest or in persisted WorkManager rows.
-keepnames class io.github.willianchavez.winali.DownloadWorker
-keepnames class io.github.willianchavez.winali.UpdateWorker
-keepnames class io.github.willianchavez.winali.InstallReceiver

# Readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

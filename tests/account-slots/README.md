# Account slots and private-storage regression

Run from the repository root with JDK 17:

```sh
./gradlew --no-daemon --console=plain -p tests/account-slots run
```

The executable compiles the actual AccountSlotRegistry, StoreAccess, StoreFile,
PrivateStorage, StorageBackup, JsonUtil and UserEntity sources. No Android SDK is
required. The production storage engine performs the transactions, semaphore
serialization, resource resolution, file syncing, atomic replacements, backup
hash validation, restore installation and interrupted-restore recovery.

32 scenarios run twice: through module-owner access and host-session access
(64 checks). They cover two-account admission, 1,000 alternating UID switches per
backend, confirmed selection persistence, empty/malformed/missing snapshots,
temporary I/O failures, cancellation, interrupted and expired provisioning,
competing clients, explicit removal, the two-slot limit, restart recovery,
private-resource handles, unavailable migration/storage, account isolation,
backup round trips, legacy transaction rollback, invalid backup paths, interrupted
atomic writes, session caller identity and client-death cleanup.

On the unmodified 4380-0.2.6 registry, 36 checks passed and 28 failed. The corrected
registry must pass all 64. Assertions use Kotlin `check`, which cannot be disabled
with JVM assertion flags.

Android Binder, Context, ParcelFileDescriptor, directory fsync, logging and the
Root/Shizuku entry points have test adapters. Host tests invoke the real production
StoreAccess.Session with emulated Binder identities and Java pipes. Concurrency
uses multiple clients of the module-owned engine; a child JVM tests sequential
process restart. These tests do not prove Android Binder IPC, platform directory
fsync, Root/Shizuku transport, on-device installation or target-app RPC behavior.

Existing registry JSON and the private-storage format remain unchanged. Never
turn StoreFile handles into ordinary File paths: their displayed paths are not
the module's private filesystem paths. Unavailable storage must fail closed,
without initializing an empty registry or modifying saved selections.

Already-removed selections cannot be reconstructed reliably: old records do not
distinguish automatic removal from intentional removal. Add those accounts once
in Automation after upgrading. Existing known accounts remain manual-only;
switching cannot undo an explicit removal. A genuinely new UID can enroll while
there is capacity; a third account remains a candidate, without execution rights.

The build workflow also compares the actual previously delivered APK and new APK:
package name and signing certificate must match, versionCode must increase.
See UPGRADE-VERIFICATION.txt in the resulting artifact for evidence and limits.

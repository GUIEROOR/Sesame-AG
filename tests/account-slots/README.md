# Account-slot regression checks

Run from the repository root with JDK 17:

```sh
./gradlew --no-daemon -p tests/account-slots run
```

This JVM executable compiles the actual `AccountSlotRegistry`, `JsonUtil`, and
`UserEntity` sources. Only Android context, storage location/listing, logging, and
scheduler cleanup are replaced with test adapters. It uses real files, JSON,
atomic replacements, file locks, threads, and child JVMs; it needs no Android SDK.

The 20 checks reproduce reads during a truncated self snapshot, unavailable directory
listings, missing account files, process restarts, interrupted provisioning,
explicit slot removal, migration, the two-slot limit, and invalid registry data.
An account's persisted selection must survive temporary file availability changes.
Only an explicit removal changes a confirmed slot; removing an unavailable account
still releases its schedules and data-store instance. A completed pending account
is confirmed after timeout. An incomplete expired reservation survives reads and
can resume for the same UID; only an eligible competing runtime UID or an explicit
manual selection reclaims it. A late confirmation for a replaced UID is rejected.
An existing config file identifies a known account even when self.json is empty,
missing or invalid, so switching cannot undo a manual removal during a rewrite.

The suite also exercises 1,000 alternating UID admissions, concurrent JVM readers,
and separate JVMs competing for the last slot. It uses `check`, not JVM assertions
that could be disabled at runtime. On the unmodified 0.2.4 registry, 9 checks pass
and 11 fail; the repaired registry passes all 20.

Already-removed selections cannot be reconstructed reliably: the old record does
not distinguish an automatic removal from an intentional one. After upgrading,
add such an account once in the automation page. The fix preserves that selection
on subsequent reads and restarts.

These checks cover registry behavior, not target-app RPC execution on a device.

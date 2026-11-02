plugins {
    kotlin("jvm") version "2.2.10"
    application
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("../../app/src/main/java")
        kotlin.include(
            "io/github/aoguai/sesameag/hook/AccountSlotRegistry.kt",
            "io/github/aoguai/sesameag/entity/UserEntity.kt",
            "io/github/aoguai/sesameag/util/JsonUtil.kt",
            "io/github/aoguai/sesameag/storage/StoreAccess.kt",
            "io/github/aoguai/sesameag/storage/StoreFile.kt",
            "io/github/aoguai/sesameag/storage/PrivateStorage.kt",
            "io/github/aoguai/sesameag/storage/StorageBackup.kt",
            "regression/**",
        )
    }
}
dependencies {
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.json:json:20240303")
}
application { mainClass.set("regression.AccountSlotRegressionKt") }

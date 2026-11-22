// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "CaochaoxingAndroid"
include(":core", ":app")

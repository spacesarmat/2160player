plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// dcerpc (smbj-rpc) тянет commons-io:2.7 / commons-lang3:3.4 с известными CVE.
// Force на patched versions для всех модулей (OSV: GHSA-78wr-2p64-hpwj, GHSA-j288-q9x7-2f5v).
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            force(
                "commons-io:commons-io:2.18.0",
                "org.apache.commons:commons-lang3:3.18.0",
            )
        }
    }
}

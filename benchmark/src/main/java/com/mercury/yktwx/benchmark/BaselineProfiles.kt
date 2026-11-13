package com.mercury.yktwx.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfiles {
    @get:Rule val profile = BaselineProfileRule()

    @Test fun startup() = profile.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
        filterPredicate = ::productionRule,
    ) {
        pressHome()
        startFixture()
    }

    @Test fun navigationAndBills() = profile.collect(packageName = TARGET_PACKAGE, filterPredicate = ::productionRule) {
        pressHome()
        startFixture()
        device.visitMainPages()
        device.visitBillDetail()
        device.scrollBills()
    }
}

private fun productionRule(rule: String) = !rule.contains("Lcom/mercury/yktwx/preview/")

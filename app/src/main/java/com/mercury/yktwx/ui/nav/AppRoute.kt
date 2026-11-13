package com.mercury.yktwx.ui.nav

import com.mercury.yktwx.data.life.LifeFeature
import com.mercury.yktwx.data.life.LifeProvider
import com.mercury.yktwx.state.BillReference
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import androidx.compose.runtime.Composable

@Serializable
internal sealed interface AppRoute : NavKey {
    @Serializable data object Main : AppRoute
    @Serializable data object Profile : AppRoute
    @Serializable data object Account : AppRoute
    @Serializable data object About : AppRoute
    @Serializable data object Settings : AppRoute
    @Serializable data object Theme : AppRoute
    @Serializable data object References : AppRoute
    @Serializable data class BillDetail(val bill: BillReference) : AppRoute
    @Serializable data class LifeDevice(
        val provider: LifeProvider = LifeProvider.Panggui,
        val feature: LifeFeature = LifeFeature.Water,
        val deviceId: String? = null,
        val sessionId: String? = null,
        /** Platform identity, never a credential. A mismatching account cannot reopen a device operation. */
        val account: String? = null,
        val skuId: String? = null,
    ) : AppRoute {
        val scanning get() = deviceId == null && sessionId == null
        val valid get() = (deviceId == null || deviceId.length in 1..256 && deviceId.none(Char::isISOControl)) &&
            (sessionId == null || sessionId.length in 1..256) && (account == null || account.length in 1..256) && (skuId == null || skuId.length in 1..256) &&
            !(sessionId != null && deviceId != null) && (provider == LifeProvider.Panggui && feature != LifeFeature.Ride ||
                provider == LifeProvider.Hui798 && feature == LifeFeature.Water || provider == LifeProvider.Hellobike && feature == LifeFeature.Ride)
    }
}

/** Miuix registers concrete runtime types; the sealed serializer alone does not register entries. */
internal fun NavEntryBuilder.appEntries(swipe: NavSwipeDirection, content: @Composable (AppRoute) -> Unit) {
    entry<AppRoute.Main>(swipeDismiss = swipe, content = content)
    entry<AppRoute.Profile>(swipeDismiss = swipe, content = content)
    entry<AppRoute.Account>(swipeDismiss = swipe, content = content)
    entry<AppRoute.About>(swipeDismiss = swipe, content = content)
    entry<AppRoute.Settings>(swipeDismiss = swipe, content = content)
    entry<AppRoute.Theme>(swipeDismiss = swipe, content = content)
    entry<AppRoute.References>(swipeDismiss = swipe, content = content)
    entry<AppRoute.BillDetail>(swipeDismiss = swipe, content = content)
    entry<AppRoute.LifeDevice>(contentKey = { "life-device" }, swipeDismiss = swipe, content = content)
}

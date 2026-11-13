package com.mercury.yktwx.ui.components

import com.mercury.yktwx.data.life.LifeFeature

internal fun lifeFeatureIcon(feature: LifeFeature) = when (feature) {
    LifeFeature.Water -> AppIcons.Water
    LifeFeature.Laundry -> AppIcons.Laundry
    LifeFeature.Ride -> AppIcons.Ride
}

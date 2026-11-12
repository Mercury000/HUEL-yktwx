package com.mercury.yktwx.di

import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.BINARY) internal annotation class ApplicationScope
@Qualifier @Retention(AnnotationRetention.BINARY) internal annotation class ServiceClient
@Qualifier @Retention(AnnotationRetention.BINARY) internal annotation class PublicClient
@Qualifier @Retention(AnnotationRetention.BINARY) internal annotation class UpdateClient

@Qualifier @Retention(AnnotationRetention.BINARY) internal annotation class OverviewReads
@Qualifier @Retention(AnnotationRetention.BINARY) internal annotation class CardReads

@Qualifier @Retention(AnnotationRetention.BINARY) internal annotation class PreferencesScope

/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.iosched.di

import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.samples.apps.iosched.shared.analytics.AnalyticsHelper
import com.google.samples.apps.iosched.shared.data.agenda.AgendaRepository
import com.google.samples.apps.iosched.shared.data.agenda.DefaultAgendaRepository
import com.google.samples.apps.iosched.shared.data.config.AppConfigDataSource
import com.google.samples.apps.iosched.shared.data.db.AppDatabase
import com.google.samples.apps.iosched.shared.data.prefs.PreferenceStorage
import com.google.samples.apps.iosched.shared.di.ApplicationScope
import com.google.samples.apps.iosched.shared.di.DefaultDispatcher
import com.google.samples.apps.iosched.shared.di.MainThreadHandler
import com.google.samples.apps.iosched.shared.domain.internal.IOSchedHandler
import com.google.samples.apps.iosched.shared.domain.internal.IOSchedMainHandler
import com.google.samples.apps.iosched.ui.signin.SignInViewModelDelegate
import com.google.samples.apps.iosched.util.FirebaseAnalyticsHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Defines all the classes that need to be provided in the scope of the app.
 *
 * Define here all objects that are shared throughout the app, like SharedPreferences, navigators or
 * others. If some of those objects are singletons, they should be annotated with `@Singleton`.
 */
@InstallIn(SingletonComponent::class)
@Module
class AppModule {

    @Provides
    fun provideWifiManager(@ApplicationContext context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Provides
    fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    @Provides
    fun provideClipboardManager(@ApplicationContext context: Context): ClipboardManager =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager

    /*
    * 全局的协程作用域，适用于计算性的工作任务，使用Supervisor保证内部任务失败不会影响外部任务
    * */
    @ApplicationScope
    @Singleton
    @Provides
    fun providesApplicationScope(
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
    ): CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    /*
    * 封装了主线程handler，用于在主线程执行任务
    * */
    @Singleton
    @Provides
    @MainThreadHandler
    fun provideMainThreadHandler(): IOSchedHandler = IOSchedMainHandler()

    /*
    * 打点库，封装成接口，方便测试以及模块隔离
    * */
    @Singleton
    @Provides
    fun provideAnalyticsHelper(
        @ApplicationScope applicationScope: CoroutineScope,
        signInDelegate: SignInViewModelDelegate,
        preferenceStorage: PreferenceStorage
    ): AnalyticsHelper =
        FirebaseAnalyticsHelper(applicationScope, signInDelegate, preferenceStorage)

    @Singleton
    @Provides
    fun provideAgendaRepository(appConfigDataSource: AppConfigDataSource): AgendaRepository =
        DefaultAgendaRepository(appConfigDataSource)

    @Singleton
    @Provides
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.buildDatabase(context)
    }

    @Singleton
    @Provides
    fun provideGson(): Gson {
        return GsonBuilder().create()
    }
}

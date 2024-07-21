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

package com.google.samples.apps.iosched.ui.signin

import android.net.Uri
import com.google.samples.apps.iosched.shared.data.signin.AuthenticatedUserInfo
import com.google.samples.apps.iosched.shared.di.ApplicationScope
import com.google.samples.apps.iosched.shared.di.IoDispatcher
import com.google.samples.apps.iosched.shared.di.MainDispatcher
import com.google.samples.apps.iosched.shared.di.ReservationEnabledFlag
import com.google.samples.apps.iosched.shared.domain.auth.ObserveUserAuthStateUseCase
import com.google.samples.apps.iosched.shared.domain.prefs.NotificationsPrefIsShownUseCase
import com.google.samples.apps.iosched.shared.result.Result
import com.google.samples.apps.iosched.shared.result.Result.Success
import com.google.samples.apps.iosched.shared.result.data
import com.google.samples.apps.iosched.shared.util.tryOffer
import com.google.samples.apps.iosched.ui.signin.SignInNavigationAction.ShowNotificationPreferencesDialog
import com.google.samples.apps.iosched.util.WhileViewSubscribed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

enum class SignInNavigationAction {
    RequestSignIn, RequestSignOut, ShowNotificationPreferencesDialog
}

/**
 * Interface to implement sign-in functionality in a ViewModel.
 * viewmodel中实现登录功能的接口，用于处理用户登录状态和相关信息的更新
 *
 * You can inject a implementation of this via Dagger2, then use the implementation as an interface
 * delegate to add sign in functionality without writing any code
 * 可以借助dagger2 注入一个实现，然后使用这个实现作为接口委托，
 *
 * Example usage
 *
 * ```
 * class MyViewModel @Inject constructor(
 *     signInViewModelComponent: SignInViewModelDelegate
 * ) : ViewModel(), SignInViewModelDelegate by signInViewModelComponent {
 * ```
 */
interface SignInViewModelDelegate {
    /**
     * Live updated value of the current firebase user
     * 表示当前用户的信息，是一个StateFlow类型，用于实时更新用户信息。
     */
    val userInfo: StateFlow<AuthenticatedUserInfo?>

    /**
     * Live updated value of the current firebase users image url
     * 表示当前用户的图像URL，是一个StateFlow类型，用于实时更新用户图像URL。
     */
    val currentUserImageUri: StateFlow<Uri?>

    /**
     * Emits Events when a sign-in event should be attempted or a dialog shown
     * 表示登录导航操作的流，是一个Flow类型，用于发送登录导航事件。
     */
    val signInNavigationActions: Flow<SignInNavigationAction>

    /**
     * Emits whether or not to show reservations for the current user
     * 表示是否显示当前用户的预订信息，是一个StateFlow类型，用于实时更新显示状态。
     */
    val showReservations: StateFlow<Boolean>

    /**
     * Emit an Event on performSignInEvent to request sign-in
     * 挂起函数，用于发送登录请求事件。
     */
    suspend fun emitSignInRequest()

    /**
     * Emit an Event on performSignInEvent to request sign-out
     * 挂起函数，用于发送登出请求事件。
     */
    suspend fun emitSignOutRequest()

    val userId: Flow<String?>

    /**
     * Returns the current user ID or null if not available.
     */
    val userIdValue: String?

    val isUserSignedIn: StateFlow<Boolean>

    val isUserSignedInValue: Boolean

    val isUserRegistered: StateFlow<Boolean>

    val isUserRegisteredValue: Boolean
}

/**
 * Implementation of SignInViewModelDelegate that uses Firebase's auth mechanisms.
 * 使用了firebase认证机制的SignInViewModelDelegate实现
 * 为什么AppModule不自己去构造SignInViewModelDelegate，而是使用inject呢
 * 构造函数注入是一种显式声明依赖关系的方法，通过 @Inject 注解直接在类的构造函数中声明依赖项。
 * 这样，依赖关系一目了然，代码更容易理解和维护。
 *
 * 为什么@ReservationEnabledFlag打在了一个boolean变量上
 * 使用 @Qualifier 注解不仅限于区分接口的不同实现，还可以用于标注特定的配置值或常量，
 * 确保依赖注入的正确性和代码的可读性。使用 @ReservationEnabledFlag
 * 注解可以明确指出这个布尔值的用途,比如从这个类注入
 * [com.google.samples.apps.iosched.shared.di.FeatureFlagsModule.provideReservationEnabledFlag(AppConfigDataSource)]
 * 并避免与其他布尔值的冲突
 */
internal class FirebaseSignInViewModelDelegate @Inject constructor(
    observeUserAuthStateUseCase: ObserveUserAuthStateUseCase,
    private val notificationsPrefIsShownUseCase: NotificationsPrefIsShownUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @ReservationEnabledFlag val isReservationEnabledByRemoteConfig: Boolean,
    @ApplicationScope val applicationScope: CoroutineScope
) : SignInViewModelDelegate {

    private val _signInNavigationActions = Channel<SignInNavigationAction>(Channel.CONFLATED)
    override val signInNavigationActions = _signInNavigationActions.receiveAsFlow()

    private val currentFirebaseUser: Flow<Result<AuthenticatedUserInfo?>> =
        observeUserAuthStateUseCase(Any()).map {
            if (it is Result.Error) {
                Timber.e(it.exception)
            }
            it
        }

    override val userInfo: StateFlow<AuthenticatedUserInfo?> = currentFirebaseUser.map {
        (it as? Success)?.data
    }.stateIn(applicationScope, WhileViewSubscribed, null)

    override val currentUserImageUri: StateFlow<Uri?> = userInfo.map {
        it?.getPhotoUrl()
    }.stateIn(applicationScope, WhileViewSubscribed, null)

    override val isUserSignedIn: StateFlow<Boolean> = userInfo.map {
        it?.isSignedIn() ?: false
    }.stateIn(applicationScope, WhileViewSubscribed, false)

    override val isUserRegistered: StateFlow<Boolean> = userInfo.map {
        it?.isRegistered() ?: false
    }.stateIn(applicationScope, WhileViewSubscribed, false)

    init {

        /*
        * 这段代码是在Kotlin中使用Coroutines和Flow的一个例子。它在applicationScope中启动了一个新的协程，用于收集userInfo的流数据。
        * launch：用于启动一个新的协程，它不会阻塞当前线程，而是异步地执行代码块。
        * aplicationScope：指定这个协程的作用域为application级别，意味着它会在应用程序的生命周期内存在，直到应用程序被关闭。
        * userInfo.collect：collect函数用于订阅userInfo的流数据。每当userInfo的值被更新时，块内的代码就会被执行。
        * notificationsPrefIsShownUseCase(Unit).data：调用一个用例（可能是从依赖注入或某个仓库获取的），用于获取通知偏好是否已显示的布尔值。Unit作为参数传递给用例，表示不需要任何输入。
        * isUserSignedInValue：一个表示用户是否已登录的布尔值。
        * _signInNavigationActions.tryOffer(ShowNotificationPreferencesDialog)：如果通知偏好未显示且用户已登录，则尝试向_signInNavigationActions通道提供ShowNotificationPreferencesDialog动作。这个动作可能用于导航到通知偏好设置的界面。
        * 总结：这段代码的作用是在用户已登录且通知偏好未显示的情况下，尝试触发导航到通知偏好设置的界面。
        *
        * */
        applicationScope.launch {
            userInfo.collect {
                // 还没有向用户显示通知偏好设置时，并且用户已经登录，发射登录导航事件，触发用户显示通知偏好设置对话框
                if (notificationsPrefIsShownUseCase(Unit).data == false && isUserSignedInValue) {
                    _signInNavigationActions.tryOffer(ShowNotificationPreferencesDialog)
                }
            }
        }
    }

    // map映射传入的表达式：如果用户已注册或未登录，并且服务端对终端的预约功能已启用，则结果为 true。
    override val showReservations: StateFlow<Boolean> = userInfo.map {
        (isUserRegisteredValue || !isUserSignedInValue) &&
            isReservationEnabledByRemoteConfig
        // stateIn 是一个 Flow 操作符，它将流转换为一个 StateFlow，允许观察其当前状态。
        // 为什么用statein：map返回一个冷流，将其转换为一个热流
    }.stateIn(applicationScope, WhileViewSubscribed, false)

    override suspend fun emitSignInRequest(): Unit = withContext(ioDispatcher) {
        // Refresh the notificationsPrefIsShown because it's used to indicate if the
        // notifications preference dialog should be shown
        notificationsPrefIsShownUseCase(Unit)
        _signInNavigationActions.tryOffer(SignInNavigationAction.RequestSignIn)
    }

    override suspend fun emitSignOutRequest(): Unit = withContext(mainDispatcher) {
        _signInNavigationActions.tryOffer(SignInNavigationAction.RequestSignOut)
    }

    override val isUserSignedInValue: Boolean
        get() = isUserSignedIn.value

    override val isUserRegisteredValue: Boolean
        get() = isUserRegistered.value

    override val userIdValue: String?
        get() = userInfo.value?.getUid()

    override val userId: StateFlow<String?>
        get() = userInfo.mapLatest { it?.getUid() }
            .stateIn(applicationScope, WhileViewSubscribed, null)
}

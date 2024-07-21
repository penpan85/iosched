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

package com.google.samples.apps.iosched.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.samples.apps.iosched.ui.LaunchNavigatonAction.NavigateToMainActivityAction
import com.google.samples.apps.iosched.ui.LaunchNavigatonAction.NavigateToOnboardingAction
import com.google.samples.apps.iosched.ui.onboarding.OnboardingActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * A 'Trampoline' activity for sending users to an appropriate screen on launch.
 */
@AndroidEntryPoint
class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel: LaunchViewModel by viewModels()

        /*
        Lifecycle 库的一个扩展函数，主要用于在特定的生命周期状态下重复执行协程
        * 在指定的生命周期状态下启动一个新的协程
        * 当生命周期退出指定状态时自动取消该协程
        * 当生命周期重新进入指定状态时，再次启动一个新的协程
        * 详细解释：
        * lifecycleScope.launch 启动了一个新的协程，这个协程的生命周期与 Activity 绑定。
        * lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) 指定了在 STARTED 状态下执行内部的代码块。
        * viewModel.launchDestination.collect { ... } 开始收集 ViewModel 中的 launchDestination 流。
        * 当 Activity 进入 STARTED 状态时，collect 操作开始执行。
        * 如果 Activity 退出 STARTED 状态（例如进入后台），repeatOnLifecycle 会自动取消当前的协程，从而停止 collect 操作。
        * 如果 Activity 重新进入 STARTED 状态，repeatOnLifecycle 会重新启动一个新的协程，再次开始 collect 操作。

        * 虽然你没有看到明确的退出操作，但 repeatOnLifecycle 函数在内部处理了这些细节：
        * 当 Activity 被暂停或停止时，collect 操作会被自动取消。
        * 当 Activity 被销毁时，lifecycleScope 会取消所有关联的协程。
        *
        * */
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.launchDestination.collect { action ->
                    when (action) {
                        is NavigateToMainActivityAction -> startActivity(
                            Intent(this@LauncherActivity, MainActivity::class.java)
                        )
                        is NavigateToOnboardingAction -> startActivity(
                            Intent(this@LauncherActivity, OnboardingActivity::class.java)
                        )
                    }
                    finish()
                }
            }
        }
    }
}

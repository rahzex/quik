/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.common

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.uber.rxdogtag.RxDogTag
import com.uber.rxdogtag.autodispose.AutoDisposeConfigurer
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import dagger.android.HasBroadcastReceiverInjector
import dagger.android.HasServiceInjector
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.FileLoggingTree
import dev.octoshrimpy.quik.injection.AppComponentManager
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.interactor.SpeakThreads
import dev.octoshrimpy.quik.manager.BillingManager
import dev.octoshrimpy.quik.manager.ReferralManager
import dev.octoshrimpy.quik.migration.QkMigration
import dev.octoshrimpy.quik.migration.QkRealmMigration
import dev.octoshrimpy.quik.repository.SyncRepository
import dev.octoshrimpy.quik.util.NightModeManager
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.worker.CategorizeAllMessagesWorker
import dev.octoshrimpy.quik.worker.HousekeepingWorker
import io.reactivex.disposables.CompositeDisposable
import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class QKApplication : Application(), HasActivityInjector, HasBroadcastReceiverInjector, HasServiceInjector {

    /**
     * Inject these so that they are forced to initialize
     */
    @Suppress("unused")
    @Inject lateinit var qkMigration: QkMigration

    @Inject lateinit var billingManager: BillingManager
    @Inject lateinit var dispatchingActivityInjector: DispatchingAndroidInjector<Activity>
    @Inject lateinit var dispatchingBroadcastReceiverInjector: DispatchingAndroidInjector<BroadcastReceiver>
    @Inject lateinit var dispatchingServiceInjector: DispatchingAndroidInjector<Service>
    @Inject lateinit var fileLoggingTree: FileLoggingTree
    @Inject lateinit var nightModeManager: NightModeManager
    @Inject lateinit var realmMigration: QkRealmMigration
    @Inject lateinit var referralManager: ReferralManager
    @Inject lateinit var workerFactory: WorkerFactory
    @Inject lateinit var prefs: Preferences
    @Inject lateinit var syncRepository: SyncRepository

    private val appDisposables = CompositeDisposable()

    override fun onCreate() {
        super.onCreate()

        // set translated "no messages" string for speakThreads interactor
        SpeakThreads.setNoMessagesString(getString(R.string.speak_no_messages))

        AppComponentManager.init(this)
        appComponent.inject(this)

        Realm.init(this)
        Realm.setDefaultConfiguration(RealmConfiguration.Builder()
                .compactOnLaunch()
                .migration(realmMigration)
                .schemaVersion(QkRealmMigration.SCHEMA_VERSION)
                .build())

        qkMigration.performMigration()

        GlobalScope.launch(Dispatchers.IO) {
            referralManager.trackReferrer()
            billingManager.checkForPurchases()
            billingManager.queryProducts()
        }

        nightModeManager.updateCurrentTheme()

        // configure timber logging
        Timber.plant(Timber.DebugTree(), fileLoggingTree)

        // configure emoji compatibility with bundled package
        // (bundled library works with no play-services/gsm os versions)
        EmojiCompat.init(BundledEmojiCompatConfig(this)
            .registerInitCallback(object: EmojiCompat.InitCallback() {
                override fun onInitialized() {
                    super.onInitialized()
                    Timber.v("bundled emojicompat initialized")
                }

                override fun onFailed(throwable: Throwable?) {
                    super.onFailed(throwable)
                    Timber.e("bundled emojicompat initialization failed")
                }
            })
        )

        // rxdogtag provides 'look-back' for exceptions in rxjava2 'chains'
        RxDogTag.builder()
                .configureWith(AutoDisposeConfigurer::configure)
                .install()

        // init work manager with custom factory supporting dagger/injection capability
        WorkManager.initialize(
            this,
            Configuration.Builder().setWorkerFactory(workerFactory).build()
        )

        // register, or re-register, housekeeping work manager
        HousekeepingWorker.register(applicationContext)

        // ── First-run SMS categorization + transaction parsing ────────────────────
        //
        // CategorizeAllMessagesWorker.enqueue() now CHAINS into ParseAllTransactionsWorker.
        // ParseAllTransactionsWorker needs TRANSACTIONS-categorised messages to exist, so
        // it must always run AFTER categorisation, never in parallel.
        //
        // Condition: run if either one-time job hasn't completed yet.
        //   • !categorizedV3Done  → brand-new install, or upgrade that requires re-categorisation
        //   • !parsedTransactionsV1Done → upgrade from pre-Phase-2 build (categorised but not parsed)
        //
        // For a fresh install the chain will find no messages (sync hasn't happened yet).
        // The post-sync subscription below re-enqueues the chain once sync completes, at
        // which point all historical messages exist and will be correctly categorised then parsed.
        //
        // WorkManager MUST be initialized (above) before we can enqueue work.
        if (!prefs.categorizedV3Done.get() || !prefs.parsedTransactionsV1Done.get()) {
            CategorizeAllMessagesWorker.enqueue(applicationContext)
        }

        // Re-run the full chain after every sync completes.
        // This is the critical fix for the first-install race condition:
        //   1. App starts → workers run before sync → find nothing → mark flags done
        //   2. Sync completes (triggered by MainViewModel) → this subscription fires
        //   3. CategorizeAllMessagesWorker runs on the now-populated message store
        //   4. ParseAllTransactionsWorker runs immediately after → Finance data populated ✓
        appDisposables.add(
            syncRepository.syncProgress
                .filter { it is SyncRepository.SyncProgress.Idle }
                .skip(1) // skip the initial Idle emission at startup
                .subscribe { CategorizeAllMessagesWorker.enqueue(applicationContext) }
        )
    }

    override fun activityInjector(): AndroidInjector<Activity> {
        return dispatchingActivityInjector
    }

    override fun broadcastReceiverInjector(): AndroidInjector<BroadcastReceiver> {
        return dispatchingBroadcastReceiverInjector
    }

    override fun serviceInjector(): AndroidInjector<Service> {
        return dispatchingServiceInjector
    }

}
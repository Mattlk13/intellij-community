// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.stats.completion

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.completion.settings.CompletionMLRankingSettings
import com.intellij.completion.tracker.PositionTrackingListener
import com.intellij.internal.statistic.utils.StatisticsUploadAssistant
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.registry.Registry
import com.intellij.reporting.isUnitTestMode
import com.intellij.stats.experiment.WebServiceStatus
import com.intellij.stats.personalization.UserFactorDescriptions
import com.intellij.stats.personalization.UserFactorStorage
import com.intellij.stats.personalization.UserFactorsManager
import com.intellij.stats.personalization.session.SessionFactorsUtils
import com.intellij.stats.personalization.session.SessionPrefixTracker
import java.beans.PropertyChangeListener
import kotlin.random.Random

class CompletionTrackerInitializer(experimentHelper: WebServiceStatus) : Disposable {
  companion object {
    var isEnabledInTests: Boolean = false
    private val LOGGED_SESSIONS_RATIO: Map<String, Double> = mapOf(
      "python" to 0.5,
      "scala" to 0.3,
      "php" to 0.3,
      "kotlin" to 0.2,
      "java" to 0.1
    )
  }

  private val actionListener = LookupActionsListener()
  private val lookupTrackerInitializer = PropertyChangeListener {
    val lookup = it.newValue
    if (lookup == null || !shouldTrackSession()) {
      actionListener.listener = CompletionPopupListener.Adapter()
    }
    else if (lookup is LookupImpl) {
      if (isUnitTestMode() && !isEnabledInTests) return@PropertyChangeListener
      val startedTimestamp = System.currentTimeMillis()
      lookup.putUserData(CompletionUtil.COMPLETION_STARTING_TIME_KEY, startedTimestamp)

      processUserFactors(lookup)
      processSessionFactors(lookup, startedTimestamp)

      if (sessionShouldBeLogged(experimentHelper, lookup.language())) {
        val tracker = actionsTracker(lookup, experimentHelper)
        actionListener.listener = tracker
        lookup.addLookupListener(tracker)
        lookup.setPrefixChangeListener(tracker)
      }
    }
  }

  init {
    initComponent()
  }

  private fun actionsTracker(lookup: LookupImpl, experimentHelper: WebServiceStatus): CompletionActionsTracker {
    val logger = CompletionLoggerProvider.getInstance().newCompletionLogger()
    return CompletionActionsTracker(lookup, logger, experimentHelper)
  }

  private fun shouldInitialize() = StatisticsUploadAssistant.isSendAllowed() || isUnitTestMode()

  private fun shouldTrackSession() = CompletionMLRankingSettings.getInstance().isCompletionLogsSendAllowed || isUnitTestMode()

  private fun shouldUseUserFactors() = UserFactorsManager.ENABLE_USER_FACTORS

  private fun shouldUseSessionFactors(): Boolean = SessionFactorsUtils.shouldUseSessionFactors()

  private fun sessionShouldBeLogged(experimentHelper: WebServiceStatus, language: Language?): Boolean {
    val application = ApplicationManager.getApplication()
    if (Registry.`is`("completion.stats.show.ml.ranking.diff")) return false
    if (application.isUnitTestMode || experimentHelper.isExperimentOnCurrentIDE()) return true
    if (!application.isEAP) return false // todo: care of released IDE versions

    var logSessionChance = 0.0
    if (language != null) {
      logSessionChance = LOGGED_SESSIONS_RATIO.getOrDefault(language.displayName.toLowerCase(), 1.0)
    }

    return Random.nextDouble() < logSessionChance
  }

  private fun processUserFactors(lookup: LookupImpl) {
    if (!shouldUseUserFactors()) return

    val userFactors = UserFactorsManager.getInstance().getAllFactors()
    val userFactorValues = mutableMapOf<String, String?>()
    userFactors.associateTo(userFactorValues) { "${it.id}:App" to it.compute(UserFactorStorage.getInstance()) }
    userFactors.associateTo(userFactorValues) { "${it.id}:Project" to it.compute(UserFactorStorage.getInstance(lookup.project)) }

    lookup.putUserData(UserFactorsManager.USER_FACTORS_KEY, userFactorValues)

    UserFactorStorage.applyOnBoth(lookup.project, UserFactorDescriptions.COMPLETION_USAGE) {
      it.fireCompletionUsed()
    }

    // setPrefixChangeListener has addPrefixChangeListener semantics
    lookup.setPrefixChangeListener(TimeBetweenTypingTracker(lookup.project))
    lookup.addLookupListener(LookupCompletedTracker())
    lookup.addLookupListener(LookupStartedTracker())
  }

  private fun processSessionFactors(lookup: LookupImpl, completionStartedTimestamp: Long) {
    if (!shouldUseSessionFactors()) return

    val storage = SessionFactorsUtils.initLookupSessionFactors(lookup, completionStartedTimestamp)
    lookup.setPrefixChangeListener(SessionPrefixTracker(storage))
    lookup.addLookupListener(LookupSelectionTracker(storage))

    val shownTimesTracker = PositionTrackingListener(lookup)
    lookup.setPrefixChangeListener(shownTimesTracker)
  }

  private fun initComponent() {
    if (!shouldInitialize()) return

    val busConnection = ApplicationManager.getApplication().messageBus.connect(this)
    busConnection.subscribe(AnActionListener.TOPIC, actionListener)
    busConnection.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
      override fun projectOpened(project: Project) {
        val lookupManager = LookupManager.getInstance(project)
        lookupManager.addPropertyChangeListener(lookupTrackerInitializer)
      }

      override fun projectClosed(project: Project) {
        val lookupManager = LookupManager.getInstance(project)
        lookupManager.removePropertyChangeListener(lookupTrackerInitializer)
      }
    })
  }

  override fun dispose() {
  }
}
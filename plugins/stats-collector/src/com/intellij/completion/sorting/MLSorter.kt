// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.completion.sorting

import com.intellij.codeInsight.completion.CompletionFinalSorter
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.completion.settings.CompletionMLRankingSettings
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.util.PsiUtilCore
import com.intellij.stats.completion.CompletionUtil
import com.intellij.stats.completion.RelevanceUtil
import com.intellij.stats.completion.prefixLength
import com.intellij.stats.experiment.EmulatedExperiment
import com.intellij.stats.experiment.WebServiceStatus
import com.intellij.stats.personalization.UserFactorsManager
import com.intellij.stats.personalization.session.SessionFactorsUtils
import com.intellij.ui.JBColor
import com.jetbrains.completion.feature.impl.FeatureUtils
import java.awt.Color
import java.util.*

@Suppress("DEPRECATION")
class MLSorterFactory : CompletionFinalSorter.Factory {
  override fun newSorter() = MLSorter()
}


class MLSorter : CompletionFinalSorter() {
  private val webServiceStatus = WebServiceStatus.getInstance()
  private val cachedScore: MutableMap<LookupElement, ItemRankInfo> = IdentityHashMap()

  override fun getRelevanceObjects(items: MutableIterable<LookupElement>): Map<LookupElement, List<Pair<String, Any>>> {
    if (cachedScore.isEmpty()) {
      return items.associate { it to listOf(Pair.create(FeatureUtils.ML_RANK, FeatureUtils.NONE as Any)) }
    }

    if (hasUnknownFeatures(items)) {
      return items.associate { it to listOf(Pair.create(FeatureUtils.ML_RANK, FeatureUtils.UNDEFINED as Any)) }
    }

    if (!isCacheValid(items)) {
      return items.associate { it to listOf(Pair.create(FeatureUtils.ML_RANK, FeatureUtils.INVALID_CACHE as Any)) }
    }

    return items.associate {
      val result = mutableListOf<Pair<String, Any>>()
      val cached = cachedScore[it]
      if (cached != null) {
        result.add(Pair.create(FeatureUtils.ML_RANK, cached.mlRank))
        result.add(Pair.create(FeatureUtils.BEFORE_ORDER, cached.positionBefore))
      }
      it to result
    }
  }

  private fun isCacheValid(items: Iterable<LookupElement>): Boolean {
    return items.map { cachedScore[it]?.prefixLength }.toSet().size == 1
  }

  private fun hasUnknownFeatures(items: Iterable<LookupElement>) = items.any {
    val score = cachedScore[it]
    score?.mlRank == null
  }

  override fun sort(items: MutableIterable<LookupElement?>, parameters: CompletionParameters): Iterable<LookupElement?> {
    val lookup = LookupManager.getActiveLookup(parameters.editor) as? LookupImpl ?: return items

    val startTime = System.currentTimeMillis()

    val itemsToSort = items.filterNotNull()
    SessionFactorsUtils.updateSessionFactors(lookup, itemsToSort)
    val languageRanker = RankingSupport.getRanker(parameters.language())
    if (languageRanker == null || !shouldSortByMlRank(languageRanker.displayName)) return items

    val sorted = sortByMLRanking(languageRanker, parameters, itemsToSort, lookup) ?: return items
    val timeSpent = System.currentTimeMillis() - startTime

    if (ApplicationManager.getApplication().isDispatchThread) {
      val totalTime = timeSpent + (lookup.getUserData(CompletionUtil.ML_SORTING_CONTRIBUTION_KEY) ?: 0)
      lookup.putUserData(CompletionUtil.ML_SORTING_CONTRIBUTION_KEY, totalTime)
    }

    return sorted
  }

  private fun shouldSortByMlRank(languageName: String): Boolean {
    val application = ApplicationManager.getApplication()
    if (application.isUnitTestMode) return false
    val settings = CompletionMLRankingSettings.getInstance()
    if (application.isEAP && webServiceStatus.isExperimentOnCurrentIDE() && settings.isCompletionLogsSendAllowed) {
      return EmulatedExperiment.shouldRank(webServiceStatus.experimentVersion())
    }

    return settings.isRankingEnabled && settings.isLanguageEnabled(languageName)
  }

  /**
   * Null means we encountered unknown features and are unable to sort them
   */
  private fun sortByMLRanking(ranker: RankingSupport.LanguageRanker,
                              parameters: CompletionParameters,
                              items: Iterable<LookupElement>,
                              lookup: LookupImpl): Iterable<LookupElement>? {
    val relevanceObjects = lookup.getRelevanceObjects(items, false)
    val prefixLength = lookup.prefixLength()
    val userFactors = lookup.getUserData(UserFactorsManager.USER_FACTORS_KEY) ?: emptyMap()
    val positionsBefore = mutableMapOf<LookupElement, Int>()
    return items
      .mapIndexed { position, lookupElement ->
        positionsBefore[lookupElement] = position
        val relevance = buildRelevanceMap(lookup, lookupElement, relevanceObjects[lookupElement], prefixLength,
                                          position, parameters, ranker.useSessionFeatures()) ?: return null
        val rank: Double = calculateElementRank(ranker, lookupElement, position, relevance, userFactors, prefixLength) ?: return null

        lookupElement to rank
      }
      .sortedByDescending { it.second }
      .map { it.first }
      .addDiagnosticsIfNeeded(positionsBefore)
  }

  private fun buildRelevanceMap(lookup: LookupImpl,
                                lookupElement: LookupElement,
                                relevanceObjects: List<Pair<String, Any?>>?,
                                prefixLength: Int,
                                position: Int,
                                parameters: CompletionParameters,
                                useSessionFeatures: Boolean): Map<String, Any>? {
    if (relevanceObjects == null) return null

    val relevanceMap = RelevanceUtil.asRelevanceMap(lookup, lookupElement, relevanceObjects, useSessionFeatures)

    relevanceMap["position"] = position
    relevanceMap["query_length"] = prefixLength
    relevanceMap["result_length"] = lookupElement.lookupString.length
    relevanceMap["auto_popup"] = parameters.isAutoPopup
    relevanceMap["completion_type"] = parameters.completionType.toString()
    relevanceMap["invocation_count"] = parameters.invocationCount

    return relevanceMap
  }

  private fun Iterable<LookupElement>.addDiagnosticsIfNeeded(positionsBefore: Map<LookupElement, Int>): Iterable<LookupElement> {
    if (Registry.`is`("completion.stats.show.ml.ranking.diff")) {
      return this.mapIndexed { position, element ->
        val diff = position - positionsBefore.getValue(element)
        if (diff != 0) {
          MyMovedLookupElement(element, diff)
        }
        else {
          element
        }
      }
    }

    return this
  }

  private fun getCachedRankInfo(element: LookupElement, prefixLength: Int, position: Int): ItemRankInfo? {
    val cached = cachedScore[element]
    if (cached != null && prefixLength == cached.prefixLength && cached.positionBefore == position) {
      return cached
    }
    return null
  }

  private fun calculateElementRank(ranker: RankingSupport.LanguageRanker,
                                   element: LookupElement,
                                   position: Int,
                                   relevance: Map<String, Any>,
                                   userFactors: Map<String, Any?>,
                                   prefixLength: Int): Double? {
    val cachedWeight = getCachedRankInfo(element, prefixLength, position)
    if (cachedWeight != null) {
      return cachedWeight.mlRank
    }

    val unknownFactors = ranker.unknownFeatures(relevance.keys)
    val mlRank: Double? = if (unknownFactors.isEmpty()) ranker.rank(relevance, userFactors) else null
    val info = ItemRankInfo(position, mlRank, prefixLength)
    cachedScore[element] = info

    return info.mlRank
  }
}

private class MyMovedLookupElement(delegate: LookupElement,
                                   private val diff: Int) : LookupElementDecorator<LookupElement>(delegate) {
  private companion object {
    val ML_RANK_DIFF_GREEN_COLOR = JBColor(JBColor.GREEN.darker(), JBColor.GREEN.brighter())
  }

  override fun renderElement(presentation: LookupElementPresentation) {
    super.renderElement(presentation)
    if (!presentation.isReal) return
    val text = if (diff < 0) " ↑${-diff} " else " ↓$diff "
    val color: Color = if (diff < 0) ML_RANK_DIFF_GREEN_COLOR else JBColor.RED

    val fragments = presentation.tailFragments
    presentation.setTailText(text, color)
    for (fragment in fragments) {
      presentation.appendTailText(fragment.text, fragment.isGrayed)
    }
  }
}

private data class ItemRankInfo(val positionBefore: Int, val mlRank: Double?, val prefixLength: Int)

fun CompletionParameters.language(): Language? {
  val offset = editor.caretModel.offset
  return PsiUtilCore.getLanguageAtOffset(originalFile, offset)
}
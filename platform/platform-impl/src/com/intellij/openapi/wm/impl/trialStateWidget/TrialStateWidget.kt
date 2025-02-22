// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.trialStateWidget

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.ui.GotItTooltip
import com.intellij.ui.dsl.gridLayout.GridLayout
import com.intellij.ui.dsl.gridLayout.VerticalAlign
import com.intellij.ui.dsl.gridLayout.builders.RowsGridBuilder
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.launchOnShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.min

internal class TrialStateWidget : DumbAwareAction(), CustomComponentAction {

  private var tooltip: GotItTooltip? = null

  override fun actionPerformed(e: AnActionEvent) {
    TrialStateService.getInstance().setLastShownColorStateClicked()
    TrialStateUtils.openTrailStateTab()
  }

  override fun getActionUpdateThread(): ActionUpdateThread {
    return ActionUpdateThread.BGT
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = TrialStateService.isEnabled() && TrialStateService.getInstance().state.value != null
  }


  override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
    val result = TrialStateButtonWrapper()

    result.launchOnShow("TrialStateButton") {
      TrialStateService.getInstance().state.collect { state ->
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
          updateButton(result)

          if (state?.trialStateChanged == true) {
            showGotItTooltip(result, state)
          }
        }
      }
    }

    result.button.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent?) {
        ActionManager.getInstance().tryToExecute(this@TrialStateWidget, e, null, place, false)
      }
    })

    return result
  }

  override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
    updateButton(component as TrialStateButtonWrapper)
  }

  private fun showGotItTooltip(wrapper: TrialStateButtonWrapper, state: TrialStateService.State) {
    if (!wrapper.isShowing()) {
      return
    }

    disposeTooltip()

    tooltip = state.getGotItTooltip()
    tooltip?.show(wrapper.button) { it, _ ->
      val width = min(it.width, (it as JComponent).visibleRect.width)
      Point(width - JBUIScale.scale(20), it.height)
    }
  }

  private fun disposeTooltip() {
    tooltip?.let {
      Disposer.dispose(it)
    }
    tooltip = null
  }

  private fun updateButton(wrapper: TrialStateButtonWrapper) {
    val state = TrialStateService.getInstance().state.value ?: return

    with(wrapper.button) {
      setColorState(state.colorState)
      text = state.getButtonText()
    }
  }
}

/**
 * Prevent button vertical stretching
 */
private class TrialStateButtonWrapper : JPanel(GridLayout()) {

  val button = TrialStateButton()

  init {
    isOpaque = false

    RowsGridBuilder(this)
      .resizableRow()
      .cell(button, verticalAlign = VerticalAlign.CENTER)
  }
}

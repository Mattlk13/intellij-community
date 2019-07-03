// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.hints

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.hints.settings.Diff
import com.intellij.codeInsight.hints.settings.ParameterNameHintsSettings
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.CodeInsightColors.ERRORS_ATTRIBUTES
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.SwingActionLink
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.*


class BlackListDialog(val language: Language, private val patternToAdd: String? = null) : DialogWrapper(null) {
  lateinit var myEditor: EditorTextField
  private var myPatternsAreValid = true

  init {
    title = "Parameter Name Hints Blacklist"
    init()
  }

  override fun createCenterPanel(): JComponent? {
    return createBlacklistPanel(language)
  }


  private fun createBlacklistPanel(language: Language): JPanel? {
    val provider = InlayParameterHintsExtension.forLanguage(language)
    if (!provider.isBlackListSupported) return null

    val blackList = getLanguageBlackList(language)
    val finalText = if (patternToAdd != null) {
      blackList + "\n" + patternToAdd
    } else {
      blackList
    }
    val editorTextField = createBlacklistEditorField(finalText)
    editorTextField.addDocumentListener(object : DocumentListener {
      override fun documentChanged(e: DocumentEvent) {
        updateOkEnabled(editorTextField)
      }
    })
    updateOkEnabled(editorTextField)

    myEditor = editorTextField

    val mainPanel = JPanel(BorderLayout())
    val blacklistPanel = JPanel()
    val layout = BoxLayout(blacklistPanel, BoxLayout.Y_AXIS)
    blacklistPanel.layout = layout

    mainPanel.add(blacklistPanel, BorderLayout.NORTH)

    val resetPanel = createResetPanel(language)
    resetPanel.alignmentX = Component.LEFT_ALIGNMENT
    blacklistPanel.add(resetPanel)
    blacklistPanel.add(Box.createRigidArea(Dimension(0, 5)))

    editorTextField.alignmentX = Component.LEFT_ALIGNMENT
    blacklistPanel.add(editorTextField)

    val label = createBlacklistDependencyInfoLabel(language)
    if (label != null) {
      label.alignmentX = Component.LEFT_ALIGNMENT
      blacklistPanel.add(label)
      mainPanel.add(label, BorderLayout.CENTER)
    }

    val explanationPanel = explanationPanel()
    explanationPanel.alignmentX = Component.LEFT_ALIGNMENT
    mainPanel.add(explanationPanel, BorderLayout.SOUTH)
    return mainPanel
  }

  private fun explanationPanel() : JPanel {
    val panel = JPanel()
    panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.add(Box.createRigidArea(JBUI.size(0, 10)))
    panel.add(JBLabel(getBlacklistExplanationHTML(language)))
    panel.add(Box.createRigidArea(JBUI.size(0, 10)))
    return panel
  }

  private fun createResetPanel(language: Language): JComponent {
    val link = SwingActionLink(object : AbstractAction("Reset") {
      override fun actionPerformed(e: ActionEvent) {
        setLanguageBlacklistToDefault(language)
      }
    })

    val box = Box.createHorizontalBox()
    box.add(Box.createHorizontalGlue())
    box.add(link)

    return box
  }

  private fun setLanguageBlacklistToDefault(language: Language) {
    val provider = InlayParameterHintsExtension.forLanguage(language)
    val defaultBlacklist = provider!!.defaultBlackList
    myEditor.text = StringUtil.join(defaultBlacklist, "\n")
  }

  private fun updateOkEnabled(editorTextField: EditorTextField) {
    val text = editorTextField.text
    val invalidLines = getBlackListInvalidLineNumbers(text)
    myPatternsAreValid = invalidLines.isEmpty()

    okAction.isEnabled = myPatternsAreValid

    val editor = editorTextField.editor
    if (editor != null) {
      highlightErrorLines(invalidLines, editor)
    }
  }

  override fun doOKAction() {
    super.doOKAction()
    val blacklist = myEditor.text
    storeBlackListDiff(language, blacklist)
  }

  private fun storeBlackListDiff(language: Language, text: String) {
    val updatedBlackList = text.split("\n").filter { e -> e.trim { it <= ' ' }.isNotEmpty() }.toSet()

    val provider = InlayParameterHintsExtension.forLanguage(language)
    val defaultBlackList = provider.defaultBlackList
    val diff = Diff.build(defaultBlackList, updatedBlackList)
    ParameterNameHintsSettings.getInstance().setBlackListDiff(getLanguageForSettingKey(language), diff)
    ParameterHintsPassFactory.forceHintsUpdateOnNextPass()
  }
}



private fun getLanguageBlackList(language: Language): String {
  val hintsProvider = InlayParameterHintsExtension.forLanguage(language) ?: return ""
  val diff = ParameterNameHintsSettings.getInstance().getBlackListDiff(getLanguageForSettingKey(language))
  val blackList = diff.applyOn(hintsProvider.defaultBlackList)
  return StringUtil.join(blackList, "\n")
}

private fun createBlacklistEditorField(text: String): EditorTextField {
  val document = EditorFactory.getInstance().createDocument(text)
  val field = EditorTextField(document, null, FileTypes.PLAIN_TEXT, false, false)
  field.preferredSize = Dimension(200, 350)
  field.addSettingsProvider { editor ->
    editor.setVerticalScrollbarVisible(true)
    editor.setHorizontalScrollbarVisible(true)
    editor.settings.additionalLinesCount = 2
    highlightErrorLines(getBlackListInvalidLineNumbers(text), editor)
  }
  return field
}

private fun highlightErrorLines(lines: List<Int>, editor: Editor) {
  val attributes = editor.colorsScheme.getAttributes(ERRORS_ATTRIBUTES)
  val document = editor.document
  val totalLines = document.lineCount

  val model = editor.markupModel
  model.removeAllHighlighters()
  lines.stream()
    .filter { current -> current < totalLines }
    .forEach { line -> model.addLineHighlighter(line!!, HighlighterLayer.ERROR, attributes) }
}

private fun getBlacklistExplanationHTML(language: Language): String {
  val hintsProvider = InlayParameterHintsExtension.forLanguage(language) ?: return CodeInsightBundle.message(
    "inlay.hints.blacklist.pattern.explanation")
  return hintsProvider.blacklistExplanationHTML
}

private fun createBlacklistDependencyInfoLabel(language: Language): JBLabel? {
  val provider = InlayParameterHintsExtension.forLanguage(language)
  val dependencyLanguage = provider.blackListDependencyLanguage ?: return null
  return JBLabel(CodeInsightBundle.message("inlay.hints.base.blacklist.description", dependencyLanguage.displayName), SwingConstants.RIGHT)
}
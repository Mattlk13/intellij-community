// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.application.options.editor

import com.intellij.ide.ui.UINumericRange
import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettings.Companion.TABS_NONE
import com.intellij.openapi.application.ApplicationBundle.message
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.layout.*
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.SwingConstants

class EditorTabsConfigurable : BoundConfigurable("Editor Tabs", "reference.settingsdialog.IDE.editor.tabs"), EditorOptionsProvider {
  companion object {
    private val EDITOR_TABS_RANGE = UINumericRange(10, 1, Math.max(10, Registry.intValue("ide.max.editor.tabs", 100)))
  }

  private lateinit var myEditorTabPlacement: JComboBox<Int>
  private lateinit var myScrollTabLayoutInEditorCheckBox: JCheckBox

  override fun createPanel(): DialogPanel {
    return panel {
      titledRow(message("group.tab.appearance")) {
        row {
          cell {
            label(TAB_PLACEMENT + ":")
            myEditorTabPlacement = tabPlacementComboBox().component
          }
        }
        row {
          myScrollTabLayoutInEditorCheckBox =
            checkBox(scrollTabLayoutInEditor).enableIf(myEditorTabPlacement.selectedValueIs(SwingConstants.TOP)).component
          row {
            checkBox(hideTabsIfNeeded).enableIf(myEditorTabPlacement.selectedValueMatches { it != TABS_NONE }
                                                  and myScrollTabLayoutInEditorCheckBox.selected)
          }
        }
        row { checkBox(showFileException).enableIfTabsVisible() }
        row { checkBox(showDirectoryForNonUniqueFilenames).enableIfTabsVisible() }
        row { checkBox(markModifiedTabsWithAsterisk).enableIfTabsVisible() }
        row { checkBox(showTabsTooltips).enableIfTabsVisible() }
        row {
          cell {
            label(CLOSE_BUTTON_POSITION + ":")
            closeButtonPositionComboBox()
          }
        }.enableIf((myEditorTabPlacement.selectedValueMatches { it != TABS_NONE }))
      }
      titledRow(message("group.tab.order")) {
        row { checkBox(sortTabsAlphabetically) }.enableIf(myScrollTabLayoutInEditorCheckBox.selected
                                                            or myEditorTabPlacement.selectedValueIs(SwingConstants.LEFT)
                                                            or myEditorTabPlacement.selectedValueIs(SwingConstants.RIGHT))
        row { checkBox(openTabsAtTheEnd) }
      }
      titledRow(message("group.tab.closing.policy")) {
        row {
          cell {
            label(message("editbox.tab.limit"))
            intTextField(ui::editorTabLimit, 4, EDITOR_TABS_RANGE)
          }
        }
        row {
          label(message("label.when.number.of.opened.editors.exceeds.tab.limit"))
          buttonGroup {
            row { radioButton(message("radio.close.non.modified.files.first"), ui::closeNonModifiedFilesFirst) }
            row { radioButton(message("radio.close.less.frequently.used.files")) }.largeGapAfter()
          }
        }
        row {
          label(message("label.when.closing.active.editor"))
          buttonGroup {
            row { radioButton(message("radio.activate.left.neighbouring.tab")) }
            row { radioButton(message("radio.activate.right.neighbouring.tab"), ui::activeRightEditorOnClose) }
            row { radioButton(message("radio.activate.most.recently.opened.tab"), ui::activeMruEditorOnClose) }.largeGapAfter()
          }
        }
        row {
          checkBox(reuseNotModifiedTabs)
        }
      }
    }
  }

  private fun <T : JComponent> CellBuilder<T>.enableIfTabsVisible() {
    enableIf(myEditorTabPlacement.selectedValueMatches { it != TABS_NONE })
  }

  override fun apply() {
    val uiSettingsChanged = isModified
    super.apply()

    if (uiSettingsChanged) {
      UISettings.instance.fireUISettingsChanged()
    }
  }

  override fun getId() = ID
}
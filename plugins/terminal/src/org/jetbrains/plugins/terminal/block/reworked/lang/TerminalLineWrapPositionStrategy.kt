// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.block.reworked.lang

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.LineWrapPositionStrategy
import com.intellij.openapi.project.Project

/**
 * The Terminal requires the simplest line wrapping logic:
 * part of the logical line that doesn't fit into the width, should be moved to the next visual line.
 * So, actually, it is a hard wrap.
 */
internal class TerminalLineWrapPositionStrategy : LineWrapPositionStrategy {
  override fun calculateWrapPosition(
    document: Document,
    project: Project?,
    startOffset: Int,
    endOffset: Int,
    maxPreferredOffset: Int,
    allowToBeyondMaxPreferredOffset: Boolean,
    isSoftWrap: Boolean,
  ): Int {
    // Wrap after the last character that fits into the required width
    return maxPreferredOffset - 1
  }


  /**
   * By default, disallows breaking before low surrogate characters to prevent break inside of surrogate pairs.
   */
  override fun canWrapLineAtOffset(text: CharSequence, offset: Int): Boolean {
    val c: Char = text[offset]
    // Ensure no break occurs within surrogate pairs.
    if (Character.isLowSurrogate(c)) {
      if (offset - 1 >= 0 && Character.isHighSurrogate(text.get(offset - 1))) {
        return false
      }
    }
    return true
  }
}
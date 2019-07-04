// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.wm.impl.customFrameDecorations.header.titleLabel

import net.miginfocom.swing.MigLayout
import sun.swing.SwingUtilities2
import java.awt.Color
import java.awt.Font
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.plaf.FontUIResource


class ProjectTitlePane : ShrinkingTitlePart {
  private val unparsed = object : DefaultPartTitle() {
    override val label: TitleLabel = BoldTitleLabel()
  }

  private val projectTitle = ProjectTitle()
  var parsed = false

  private val pane = object : JPanel(MigLayout("ins 0, gap 0, hidemode 3", "[pref][pref]")){
    override fun setForeground(fg: Color?) {
      super.setForeground(fg)
      projectTitle.component.foreground = fg
      unparsed.component.foreground = fg
    }
  }.apply {
    add(unparsed.component)
    add(projectTitle.component)
    isOpaque = false
  }

  override val component: JComponent
    get() = pane

  private var state = TitlePart.State.LONG

  fun setProject(lng: String, short: String) {
    val long = if (lng.length > short.length) lng else short

    unparsed.longText = long
    unparsed.shortText = short

    val regex = """(.*)(\[)(.*)(])(.*)""".toRegex()
    val regex1 = """(.*)(\()(.*)(\))(.*)""".toRegex()
    val regex2 = """(.*)(<)(.*)(>)(.*)""".toRegex()
    val regex3 = """(.*)(\{)(.*)(})(.*)""".toRegex()
    val match = regex.matchEntire(long)
                ?: regex1.matchEntire(long)
                ?: regex2.matchEntire(long)
                ?: regex3.matchEntire(long)

    parsed = match?.let {
      val (before, open, path, close, after) = match.destructured

      val project = before.trim()
      if (project == short && path.isNotEmpty() && File(path).exists()) {
        projectTitle.project = project
        projectTitle.openChar = " $open"
        projectTitle.closeChar = close
        projectTitle.path = path

        true
      }
      else false
    } ?: false

    unparsed.component.isVisible = !parsed
    projectTitle.component.isVisible = parsed

  }

  override val longWidth: Int
    get() = if (parsed) projectTitle.longWidth else unparsed.longWidth
  override val shortWidth: Int
    get() = if (parsed) projectTitle.shortWidth else unparsed.shortWidth
  override val toolTip: String
    get() = if (parsed) projectTitle.toolTip else unparsed.toolTip
  override val isClipped: Boolean
    get() = if (parsed) projectTitle.isClipped else unparsed.isClipped

  override fun ignore() {
    state = TitlePart.State.IGNORED
    unparsed.ignore()
  }

  override fun hide() {
    state = TitlePart.State.HIDE
    unparsed.hide()
    projectTitle.hide()
  }

  override fun showLong() {
    state = TitlePart.State.LONG
    unparsed.showLong()
    projectTitle.showLong()
  }

  override fun showShort() {
    state = TitlePart.State.SHORT
    unparsed.showShort()
    projectTitle.showShort()
  }

  override fun refresh() {
    unparsed.refresh()
    projectTitle.refresh()
  }

  override fun shrink(maxWidth: Int): Int {
    return when {
      parsed -> {
        projectTitle.shrink(maxWidth)
      }
      else -> {
        unparsed.component.isVisible = true
        projectTitle.component.isVisible = false

        return if (maxWidth > unparsed.longWidth) {
          unparsed.showLong()
          unparsed.longWidth
        }
        else {
          unparsed.showShort()
          unparsed.shortWidth
        }
      }
    }
  }
}

class ProjectTitle : ShrinkingTitlePart {
  private val label = BoldTitleLabel()
  private val description = ClippingTitle()

  private var projectTextWidth: Int = 0
  private var longTextWidth: Int = 0

  private var state = TitlePart.State.LONG

  private val pane = object : JPanel(MigLayout("ins 0, gap 0", "[pref][pref]")){
    override fun setForeground(fg: Color?) {
      super.setForeground(fg)
      label.foreground = fg
      description.component.foreground = fg
    }
  }.apply {
    add(label)
    add(description.component)
    isOpaque = false
  }

  override val component: JComponent
    get() = pane

  var openChar: String
    get() = description.prefix
    set(value) {
      description.prefix = value
    }

  var closeChar: String
    get() = description.suffix
    set(value) {
      description.suffix = value
    }

  var path: String
    get() = description.longText
    set(value) {
      description.longText = value
    }

  var project: String = ""

  override val longWidth: Int
    get() = longTextWidth
  override val shortWidth: Int
    get() = projectTextWidth
  override val toolTip: String
    get() = if (state == TitlePart.State.IGNORED || project.isEmpty()) "" else project

  override fun hide() {
    state = TitlePart.State.HIDE
    label.text = ""
  }

  override val isClipped: Boolean
    get() = !(state == TitlePart.State.LONG || state == TitlePart.State.IGNORED)

  override fun ignore() {
    state = TitlePart.State.IGNORED
    description.hide()
    label.text = ""
  }

  override fun showLong() {
    label.text = project
    description.showLong()
    state = TitlePart.State.LONG
  }

  override fun showShort() {
    label.text = project
    description.hide()
    state = TitlePart.State.SHORT
  }

  override fun shrink(maxWidth: Int): Int {
    return when {
      maxWidth > longWidth -> {
        label.text = project
        description.showLong()
        longWidth
      }
      maxWidth > shortWidth + description.shortWidth -> {
        label.text = project
        description.shrink(maxWidth - shortWidth) + shortWidth
      }
      else -> {
        label.text = project
        description.hide()
        shortWidth
      }
    }
  }

  override fun refresh() {
    description.refresh()
    val fm = label.getFontMetrics(label.font)

    projectTextWidth = if (project.isEmpty()) 0 else SwingUtilities2.stringWidth(label, fm, project)
    longTextWidth = projectTextWidth + description.longWidth
  }
}

class BoldTitleLabel : DefaultPartTitle.TitleLabel() {
  private fun fontUIResource(font: Font) = FontUIResource(font.deriveFont(font.style or Font.BOLD))

  override fun setFont(font: Font) {
    super.setFont(fontUIResource(font))
  }

  init {
    font = fontUIResource(font)
  }
}
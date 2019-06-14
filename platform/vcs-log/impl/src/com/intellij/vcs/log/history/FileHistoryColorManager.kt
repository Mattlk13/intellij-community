// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.log.history

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.VcsLogDataPack
import com.intellij.vcs.log.history.FileHistoryVisiblePack.Companion.filePaths
import com.intellij.vcs.log.ui.VcsLogColorManager
import com.intellij.vcs.log.ui.VcsLogColorManagerImpl
import com.intellij.vcsUtil.VcsFileUtil
import java.awt.Color

class FileHistoryColorManager(private val root: VirtualFile,
                              private val path: FilePath) : VcsLogColorManager {
  private var baseColorManager = VcsLogColorManagerImpl(setOf(path))

  override fun getPathColor(path: FilePath): Color {
    return baseColorManager.getPathColor(path)
  }

  fun update(pack: VcsLogDataPack) {
    val pathsFromPack = pack.filePaths()
    if (pathsFromPack.isEmpty()) {
      baseColorManager = VcsLogColorManagerImpl(setOf(path))
    }
    else {
      val newPaths = mutableListOf<FilePath>()
      for (path in baseColorManager.paths) {
        if (pathsFromPack.contains(path)) newPaths.add(path)
      }
      for (path in pathsFromPack) {
        if (!newPaths.contains(path)) newPaths.add(path)
      }
      
      baseColorManager = VcsLogColorManagerImpl(newPaths)
    }
  }

  override fun getPaths(): Collection<FilePath> {
    return baseColorManager.paths
  }

  override fun getLongName(path: FilePath): String {
    return VcsFileUtil.relativePath(root, path)
  }
}

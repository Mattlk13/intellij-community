// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.diagnostic;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Comparing;
import gnu.trove.THashSet;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@ApiStatus.Internal
public enum LoadingPhase {
  BOOTSTRAP,
  SPLASH,
  COMPONENT_REGISTERED,
  CONFIGURATION_STORE_INITIALIZED,
  COMPONENT_LOADED,
  FRAME_SHOWN,
  PROJECT_OPENED,
  INDEXING_FINISHED;

  @NotNull
  private static Logger getLogger() {
    return Logger.getInstance(LoadingPhase.class);
  }

  private final static boolean SKIP_LOADING_PHASE = Boolean.parseBoolean("idea.skip.loading.phase");

  public static void setCurrentPhase(@NotNull LoadingPhase phase) {
    LoadingPhase old = currentPhase.getAndSet(phase);
    if (old.ordinal() > phase.ordinal()) {
      getLogger().error("New phase " + phase + " cannot be earlier then old " + old);
    }
    logPhaseSet(phase);
  }

  private final static Set<Throwable> stackTraces = new THashSet<>(new TObjectHashingStrategy<Throwable>() {
    @Override
    public int computeHashCode(Throwable throwable) {
      return getCollect(throwable).hashCode();
    }

    private String getCollect(Throwable throwable) {
      return Arrays
        .stream(throwable.getStackTrace())
        .map(element -> element.getClassName() + element.getMethodName())
        .collect(Collectors.joining());
    }

    @Override
    public boolean equals(Throwable o1, Throwable o2) {
      if (o1 == o2) return true;
      if (o1 == null || o2 == null) return false;
      return Comparing.equal(getCollect(o1), getCollect(o2));
    }
  });

  private final static AtomicReference<LoadingPhase> currentPhase = new AtomicReference<>(BOOTSTRAP);

  public static void compareAndSet(@NotNull LoadingPhase expect, @NotNull LoadingPhase phase) {
    if (currentPhase.compareAndSet(expect, phase)) {
      logPhaseSet(phase);
    }
  }

  private static void logPhaseSet(@NotNull LoadingPhase phase) {
    if (phase.ordinal() >= CONFIGURATION_STORE_INITIALIZED.ordinal()) {
      getLogger().info("Reached " + phase + " loading phase");
    }
  }

  public static void assertAtLeast(@NotNull LoadingPhase phase) {
    if (SKIP_LOADING_PHASE) return;

    LoadingPhase currentPhase = LoadingPhase.currentPhase.get();
    if (currentPhase.ordinal() >= phase.ordinal()) return;

    Throwable t = new Throwable();
    synchronized (stackTraces) {
      if (!stackTraces.add(t)) return;

      getLogger().warn("Should be called at least at phase " + phase + ", the current phase is: " + currentPhase + "\n" +
                       "Current violators count: " + stackTraces.size() + "\n\n",
                       t);
    }
  }

  public static boolean isStartupComplete() {
    return INDEXING_FINISHED.isComplete();
  }

  public boolean isComplete() {
    return currentPhase.get().ordinal() >= ordinal();
  }
}

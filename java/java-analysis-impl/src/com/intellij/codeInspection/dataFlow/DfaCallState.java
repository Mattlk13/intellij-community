// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInspection.dataFlow.value.DfaValue;
import org.jetbrains.annotations.NotNull;

final class DfaCallState {
  @NotNull final DfaMemoryState myMemoryState;
  @NotNull final DfaCallArguments myCallArguments;
  @NotNull final DfaValue myReturnValue;

  DfaCallState(@NotNull DfaMemoryState state,
               @NotNull DfaCallArguments arguments,
               @NotNull DfaValue returnValue) {
    myMemoryState = state;
    myCallArguments = arguments;
    myReturnValue = returnValue;
  }

  @NotNull DfaCallState withMemoryState(@NotNull DfaMemoryState state) {
    return new DfaCallState(state, myCallArguments, myReturnValue);
  }

  @NotNull DfaCallState withArguments(@NotNull DfaCallArguments arguments) {
    return new DfaCallState(myMemoryState, arguments, myReturnValue);
  }

  @NotNull DfaCallState withReturnValue(@NotNull DfaValue returnValue) {
    return new DfaCallState(myMemoryState, myCallArguments, returnValue);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DfaCallState)) return false;
    DfaCallState that = (DfaCallState)o;
    return myMemoryState.equals(that.myMemoryState) && myCallArguments.equals(that.myCallArguments);
  }

  @Override
  public int hashCode() {
    return 31 * myMemoryState.hashCode() + myCallArguments.hashCode();
  }
}

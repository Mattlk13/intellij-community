// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.editor;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.tooltips.TooltipActionProvider;
import com.intellij.codeInsight.documentation.DocumentationComponent;
import com.intellij.codeInsight.documentation.DocumentationManager;
import com.intellij.codeInsight.documentation.QuickDocUtil;
import com.intellij.codeInsight.hint.LineTooltipRenderer;
import com.intellij.codeInsight.hint.TooltipGroup;
import com.intellij.codeInsight.hint.TooltipRenderer;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.ide.IdeEventQueue;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.editor.ex.*;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.editor.impl.EditorMouseHoverPopupControl;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.reference.SoftReference;
import com.intellij.ui.*;
import com.intellij.ui.popup.AbstractPopup;
import com.intellij.ui.popup.PopupFactoryImpl;
import com.intellij.ui.popup.PopupPositionManager;
import com.intellij.util.Alarm;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class EditorMouseHoverPopupManager implements EditorMouseMotionListener, CaretListener {
  private static final Logger LOG = Logger.getInstance(EditorMouseHoverPopupManager.class);
  private static final Key<Boolean> DISABLE_BINDING = Key.create("EditorMouseHoverPopupManager.disable.binding");
  private static final TooltipGroup EDITOR_INFO_GROUP = new TooltipGroup("EDITOR_INFO_GROUP", 0);

  private final Alarm myAlarm;
  private Point myPrevMouseLocation;
  private boolean myKeepPopupOnMouseMove;
  private WeakReference<Editor> myCurrentEditor;
  private WeakReference<AbstractPopup> myPopupReference;
  private Context myContext;
  private ProgressIndicator myCurrentProgress;

  public EditorMouseHoverPopupManager(Application application, EditorFactory editorFactory, EditorMouseHoverPopupControl control) {
    myAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, application);
    editorFactory.getEventMulticaster().addEditorMouseMotionListener(this);
    editorFactory.getEventMulticaster().addCaretListener(this);
    control.addListener(() -> {
      if (!Registry.is("editor.new.mouse.hover.popups")) return;
      Editor editor = SoftReference.dereference(myCurrentEditor);
      if (editor != null && EditorMouseHoverPopupControl.arePopupsDisabled(editor)) {
        closeHint();
      }
    });
  }

  @Override
  public void mouseMoved(@NotNull EditorMouseEvent e) {
    if (!Registry.is("editor.new.mouse.hover.popups")) return;

    cancelCurrentProcessing();

    if (ignoreEvent(e)) return;

    Editor editor = e.getEditor();
    if (isPopupDisabled(editor)) {
      closeHint();
      return;
    }

    int targetOffset = getTargetOffset(e);
    if (targetOffset < 0) {
      closeHint();
      return;
    }
    Context context = createContext(editor, targetOffset);
    if (context == null) {
      closeHint();
      return;
    }
    Context.Relation relation = isHintShown() ? context.compareTo(myContext) : Context.Relation.DIFFERENT;
    if (relation == Context.Relation.SAME) {
      return;
    }
    else if (relation == Context.Relation.DIFFERENT) {
      closeHint();
    }
    scheduleProcessing(editor, context, relation == Context.Relation.SIMILAR, false, false);
  }

  private void cancelCurrentProcessing() {
    myAlarm.cancelAllRequests();
    if (myCurrentProgress != null) {
      myCurrentProgress.cancel();
      myCurrentProgress = null;
    }
  }

  private void scheduleProcessing(@NotNull Editor editor,
                                  @NotNull Context context,
                                  boolean updateExistingPopup,
                                  boolean forceShowing,
                                  boolean requestFocus) {
    ProgressIndicatorBase progress = new ProgressIndicatorBase();
    myCurrentProgress = progress;
    myAlarm.addRequest(() -> ProgressManager.getInstance().executeProcessUnderProgress(() -> {
      Info info = context.calcInfo(editor);
      ApplicationManager.getApplication().invokeLater(() -> {
        if (progress != myCurrentProgress) return;
        myCurrentProgress = null;
        if (info != null &&
            editor.getContentComponent().isShowing() &&
            (forceShowing || !isPopupDisabled(editor))) {
          PopupBridge popupBridge = new PopupBridge();
          JComponent component = info.createComponent(editor, popupBridge, requestFocus);
          if (component == null) {
            closeHint();
          }
          else {
            if (updateExistingPopup && isHintShown()) {
              updateHint(component, popupBridge);
            }
            else {
              AbstractPopup hint = createHint(component, popupBridge, requestFocus);
              showHintInEditor(hint, editor, context);
              myPopupReference = new WeakReference<>(hint);
              myCurrentEditor = new WeakReference<>(editor);
            }
            myContext = context;
          }
        }
      });
    }, progress), context.getShowingDelay());
  }

  @Override
  public void caretPositionChanged(@NotNull CaretEvent event) {
    if (!Registry.is("editor.new.mouse.hover.popups")) return;

    Editor editor = event.getEditor();
    if (editor == SoftReference.dereference(myCurrentEditor)) {
      DocumentationManager.getInstance(editor.getProject()).setAllowContentUpdateFromContext(true);
    }
  }

  private boolean ignoreEvent(EditorMouseEvent e) {
    Point currentMouseLocation = e.getMouseEvent().getLocationOnScreen();
    Rectangle currentHintBounds = getCurrentHintBounds(e.getEditor());
    boolean movesTowardsPopup = ScreenUtil.isMovementTowards(myPrevMouseLocation, currentMouseLocation, currentHintBounds);
    myPrevMouseLocation = currentMouseLocation;
    if (movesTowardsPopup || currentHintBounds != null && myKeepPopupOnMouseMove) return true;

    return false;
  }

  private static boolean isPopupDisabled(Editor editor) {
    return isAnotherAppInFocus() || EditorMouseHoverPopupControl.arePopupsDisabled(editor) || LookupManager.getActiveLookup(editor) != null;
  }

  private static boolean isAnotherAppInFocus() {
    return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == null;
  }

  private Rectangle getCurrentHintBounds(Editor editor) {
    JBPopup popup = getCurrentHint();
    if (popup == null) return null;
    Dimension size = popup.getSize();
    if (size == null) return null;
    Rectangle result = new Rectangle(popup.getLocationOnScreen(), size);
    int borderTolerance = editor.getLineHeight() / 3;
    result.grow(borderTolerance, borderTolerance);
    return result;
  }

  private void showHintInEditor(AbstractPopup hint, Editor editor, Context context) {
    closeHint();
    myKeepPopupOnMouseMove = false;
    editor.putUserData(PopupFactoryImpl.ANCHOR_POPUP_POSITION, context.getPopupPosition(editor));
    try {
      PopupPositionManager.positionPopupInBestPosition(hint, editor, null);
    }
    finally {
      editor.putUserData(PopupFactoryImpl.ANCHOR_POPUP_POSITION, null);
    }
    Window window = hint.getPopupWindow();
    if (window != null) {
      window.setFocusableWindowState(true);
      IdeEventQueue.getInstance().addDispatcher(e -> {
        if (e.getID() == MouseEvent.MOUSE_PRESSED && e.getSource() == window) {
          myKeepPopupOnMouseMove = true;
        }
        return false;
      }, hint);
    }
  }

  private static AbstractPopup createHint(JComponent component, PopupBridge popupBridge, boolean requestFocus) {
    WrapperPanel wrapper = new WrapperPanel(component);
    AbstractPopup popup = (AbstractPopup)JBPopupFactory.getInstance()
      .createComponentPopupBuilder(wrapper, component)
      .setResizable(true)
      .setFocusable(requestFocus)
      .setRequestFocus(requestFocus)
      .createPopup();
    popupBridge.setPopup(popup);
    return popup;
  }

  private void updateHint(JComponent component, PopupBridge popupBridge) {
    AbstractPopup popup = getCurrentHint();
    if (popup != null) {
      WrapperPanel wrapper = (WrapperPanel)popup.getComponent();
      wrapper.setContent(component);
      validatePopupSize(popup);
      popupBridge.setPopup(popup);
    }
  }

  private static void validatePopupSize(@NotNull AbstractPopup popup) {
    JComponent component = popup.getComponent();
    if (component != null) popup.setSize(component.getPreferredSize());
  }

  private static void closePopup(AbstractPopup popup) {
    popup.cancel();

    IdeEventQueue eventQueue = IdeEventQueue.getInstance();
    AWTEvent currentEvent = eventQueue.getTrueCurrentEvent();
    if (currentEvent instanceof MouseEvent && currentEvent.getID() == MouseEvent.MOUSE_PRESSED) { // e.g. on link activation
      // this is to prevent mouse released (and dragged, dispatched due to some reason) event to be dispatched into editor
      // alternative solution would be to activate links on mouse release, not on press
      eventQueue.blockNextEvents((MouseEvent)currentEvent);
    }
  }

  private static int getTargetOffset(EditorMouseEvent event) {
    Editor editor = event.getEditor();
    Point point = event.getMouseEvent().getPoint();
    if (editor instanceof EditorEx &&
        editor.getProject() != null &&
        event.getArea() == EditorMouseEventArea.EDITING_AREA &&
        event.getMouseEvent().getModifiers() == 0 &&
        EditorUtil.isPointOverText(editor, point) &&
        ((EditorEx)editor).getFoldingModel().getFoldingPlaceholderAt(point) == null) {
      LogicalPosition logicalPosition = editor.xyToLogicalPosition(point);
      return editor.logicalPositionToOffset(logicalPosition);
    }
    return -1;
  }

  private static Context createContext(Editor editor, int offset) {
    Project project = Objects.requireNonNull(editor.getProject());

    HighlightInfo info = null;
    if (!Registry.is("ide.disable.editor.tooltips")) {
      info = ((DaemonCodeAnalyzerImpl)DaemonCodeAnalyzer.getInstance(project))
        .findHighlightByOffset(editor.getDocument(), offset, false);
    }

    PsiElement elementForQuickDoc = null;
    if (EditorSettingsExternalizable.getInstance().isShowQuickDocOnMouseOverElement()) {
      PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
      if (psiFile != null) {
        elementForQuickDoc = psiFile.findElementAt(offset);
        if (elementForQuickDoc instanceof PsiWhiteSpace || elementForQuickDoc instanceof PsiPlainText) {
          elementForQuickDoc = null;
        }
      }
    }

    return info == null && elementForQuickDoc == null ? null : new Context(offset, info, elementForQuickDoc);
  }

  private void closeHint() {
    AbstractPopup hint = getCurrentHint();
    if (hint != null) {
      hint.cancel();
    }
    myPopupReference = null;
    myCurrentEditor = null;
    myContext = null;
  }

  private boolean isHintShown() {
    return getCurrentHint() != null;
  }

  private AbstractPopup getCurrentHint() {
    if (myPopupReference == null) return null;
    AbstractPopup hint = myPopupReference.get();
    if (hint == null || !hint.isVisible()) {
      if (hint != null) {
        // hint's window might've been hidden by AWT without notifying us
        // dispose to remove the popup from IDE hierarchy and avoid leaking components
        hint.cancel();
      }
      myPopupReference = null;
      myCurrentEditor = null;
      myContext = null;
      return null;
    }
    return hint;
  }

  public void showInfoTooltip(@NotNull Editor editor,
                              @NotNull HighlightInfo info,
                              int offset,
                              boolean requestFocus,
                              boolean showImmediately) {
    cancelCurrentProcessing();
    closeHint();
    Context context = new Context(offset, info, null) {
      @Override
      long getShowingDelay() {
        return showImmediately ? 0 : super.getShowingDelay();
      }
    };
    scheduleProcessing(editor, context, false, true, requestFocus);
  }

  private static class Context {
    private final int targetOffset;
    private final WeakReference<HighlightInfo> highlightInfo;
    private final WeakReference<PsiElement> elementForQuickDoc;

    private Context(int targetOffset, HighlightInfo highlightInfo, PsiElement elementForQuickDoc) {
      this.targetOffset = targetOffset;
      this.highlightInfo = highlightInfo == null ? null : new WeakReference<>(highlightInfo);
      this.elementForQuickDoc = elementForQuickDoc == null ? null : new WeakReference<>(elementForQuickDoc);
    }

    private PsiElement getElementForQuickDoc() {
      return SoftReference.dereference(elementForQuickDoc);
    }

    private HighlightInfo getHighlightInfo() {
      return SoftReference.dereference(highlightInfo);
    }

    private Relation compareTo(Context other) {
      if (other == null) return Relation.DIFFERENT;
      HighlightInfo highlightInfo = getHighlightInfo();
      if (!Objects.equals(highlightInfo, other.getHighlightInfo())) return Relation.DIFFERENT;
      return Objects.equals(getElementForQuickDoc(), other.getElementForQuickDoc())
             ? Relation.SAME
             : highlightInfo == null ? Relation.DIFFERENT : Relation.SIMILAR;
    }

    long getShowingDelay() {
      return getHighlightInfo() == null ? EditorSettingsExternalizable.getInstance().getQuickDocOnMouseOverElementDelayMillis()
                                        : Registry.intValue("ide.tooltip.initialDelay.highlighter");
    }

    @NotNull
    private VisualPosition getPopupPosition(Editor editor) {
      HighlightInfo highlightInfo = getHighlightInfo();
      if (highlightInfo == null) {
        int offset = targetOffset;
        PsiElement elementForQuickDoc = getElementForQuickDoc();
        if (elementForQuickDoc != null) {
          offset = elementForQuickDoc.getTextRange().getStartOffset();
        }
        return editor.offsetToVisualPosition(offset);
      }
      else {
        VisualPosition targetPosition = editor.offsetToVisualPosition(targetOffset);
        VisualPosition endPosition = editor.offsetToVisualPosition(highlightInfo.getEndOffset());
        if (endPosition.line <= targetPosition.line) return targetPosition;
        Point targetPoint = editor.visualPositionToXY(targetPosition);
        Point endPoint = editor.visualPositionToXY(endPosition);
        Point resultPoint = new Point(targetPoint.x, endPoint.x > targetPoint.x ? endPoint.y : editor.visualLineToY(endPosition.line - 1));
        return editor.xyToVisualPosition(resultPoint);
      }
    }

    @Nullable
    private Info calcInfo(Editor editor) {
      HighlightInfo info = getHighlightInfo();
      if (info != null && (info.getDescription() == null || info.getToolTip() == null)) {
        info = null;
      }

      String quickDocMessage = null;
      Ref<PsiElement> targetElementRef = new Ref<>();
      if (elementForQuickDoc != null) {
        PsiElement element = getElementForQuickDoc();
        try {
          DocumentationManager documentationManager = DocumentationManager.getInstance(editor.getProject());
          QuickDocUtil.runInReadActionWithWriteActionPriorityWithRetries(() -> {
            if (element.isValid()) {
              targetElementRef.set(documentationManager.findTargetElement(editor, targetOffset, element.getContainingFile(), element));
            }
          }, 5000, 100);
          if (!targetElementRef.isNull()) {
            quickDocMessage = documentationManager.generateDocumentation(targetElementRef.get(), element);
          }
        }
        catch (IndexNotReadyException ignored) {
        }
        catch (Exception e) {
          LOG.warn(e);
        }
      }
      return info == null && quickDocMessage == null ? null : new Info(info, quickDocMessage, targetElementRef.get());
    }

    private enum Relation {
      SAME, // no need to update popup
      SIMILAR, // popup needs to be updated
      DIFFERENT // popup needs to be closed, and new one shown
    }
  }

  private static class Info {
    private final HighlightInfo highlightInfo;

    private final String quickDocMessage;
    private final WeakReference<PsiElement> quickDocElement;


    private Info(HighlightInfo highlightInfo, String quickDocMessage, PsiElement quickDocElement) {
      assert highlightInfo != null || quickDocMessage != null;
      this.highlightInfo = highlightInfo;
      this.quickDocMessage = quickDocMessage;
      this.quickDocElement = new WeakReference<>(quickDocElement);
    }

    private JComponent createComponent(Editor editor, PopupBridge popupBridge, boolean requestFocus) {
      JComponent c1 = createHighlightInfoComponent(editor, quickDocMessage == null, popupBridge, requestFocus);
      DocumentationComponent c2 = createQuickDocComponent(editor, c1 != null, popupBridge);
      if (c1 == null && c2 == null) return null;
      JPanel p = new JPanel(new CombinedPopupLayout(c1, c2));
      p.setBorder(null);
      if (c1 != null) p.add(c1);
      if (c2 != null) p.add(c2);
      return p;
    }

    private JComponent createHighlightInfoComponent(Editor editor,
                                                    boolean highlightActions,
                                                    PopupBridge popupBridge,
                                                    boolean requestFocus) {
      if (highlightInfo == null) return null;
      TooltipAction action = TooltipActionProvider.calcTooltipAction(highlightInfo, editor);
      ErrorStripTooltipRendererProvider provider = ((EditorMarkupModel)editor.getMarkupModel()).getErrorStripTooltipRendererProvider();
      TooltipRenderer tooltipRenderer = provider.calcTooltipRenderer(Objects.requireNonNull(highlightInfo.getToolTip()), action, -1);
      if (!(tooltipRenderer instanceof LineTooltipRenderer)) return null;
      return createHighlightInfoComponent(editor, (LineTooltipRenderer)tooltipRenderer, highlightActions, popupBridge, requestFocus);
    }

    private static JComponent createHighlightInfoComponent(Editor editor,
                                                           LineTooltipRenderer renderer,
                                                           boolean highlightActions,
                                                           PopupBridge popupBridge,
                                                           boolean requestFocus) {
      Ref<WrapperPanel> wrapperPanelRef = new Ref<>();
      Ref<LightweightHint> mockHintRef = new Ref<>();
      HintHint hintHint = new HintHint().setAwtTooltip(true).setRequestFocus(requestFocus);
      LightweightHint hint = renderer.createHint(editor, new Point(), false, EDITOR_INFO_GROUP, hintHint, highlightActions, expand -> {
        LineTooltipRenderer newRenderer = renderer.createRenderer(renderer.getText(), expand ? 1 : 0);
        JComponent newComponent = createHighlightInfoComponent(editor, newRenderer, highlightActions, popupBridge, requestFocus);
        AbstractPopup popup = popupBridge.getPopup();
        WrapperPanel wrapper = wrapperPanelRef.get();
        if (newComponent != null && popup != null && wrapper != null) {
          LightweightHint mockHint = mockHintRef.get();
          if (mockHint != null) closeHintIgnoreBinding(mockHint);
          wrapper.setContent(newComponent);
          validatePopupSize(popup);
        }
      });
      if (hint == null) return null;
      mockHintRef.set(hint);
      bindHintHiding(hint, popupBridge);
      WrapperPanel wrapper = new WrapperPanel(hint.getComponent());
      wrapperPanelRef.set(wrapper);
      // emulating LightweightHint+IdeTooltipManager+BalloonImpl - they use the same background
      wrapper.setBackground(hintHint.getTextBackground());
      wrapper.setOpaque(true);
      return wrapper;
    }

    private static void bindHintHiding(LightweightHint hint, PopupBridge popupBridge) {
      AtomicBoolean inProcess = new AtomicBoolean();
      hint.addHintListener(e -> {
        if (hint.getUserData(DISABLE_BINDING) == null && inProcess.compareAndSet(false, true)) {
          try {
            AbstractPopup popup = popupBridge.getPopup();
            if (popup != null) {
              closePopup(popup);
            }
          }
          finally {
            inProcess.set(false);
          }
        }
      });
      popupBridge.performOnCancel(() -> {
        if (hint.getUserData(DISABLE_BINDING) == null && inProcess.compareAndSet(false, true)) {
          try {
            hint.hide();
          }
          finally {
            inProcess.set(false);
          }
        }
      });
    }

    private static void closeHintIgnoreBinding(LightweightHint hint) {
      hint.putUserData(DISABLE_BINDING, Boolean.TRUE);
      hint.hide();
    }

    @Nullable
    private DocumentationComponent createQuickDocComponent(Editor editor,
                                                           boolean deEmphasize,
                                                           PopupBridge popupBridge) {
      if (quickDocMessage == null) return null;
      PsiElement element = quickDocElement.get();
      Project project = Objects.requireNonNull(editor.getProject());
      DocumentationManager documentationManager = DocumentationManager.getInstance(project);
      ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.DOCUMENTATION);
      if (toolWindow != null) {
        if (element != null) {
          documentationManager.showJavaDocInfo(editor, element, extractOriginalElement(element), null, quickDocMessage, true, false);
          documentationManager.setAllowContentUpdateFromContext(false);
        }
        return null;
      }
      DocumentationComponent component = new DocumentationComponent(documentationManager, false) {
        @Override
        protected void showHint() {
          AbstractPopup popup = popupBridge.getPopup();
          if (popup != null) {
            validatePopupSize(popup);
          }
        }
      };
      if (deEmphasize) {
        component.setBackground(UIUtil.getToolTipActionBackground());
        if (component.needsToolbar()) {
          component.setBorder(IdeBorderFactory.createBorder(SideBorder.TOP));
        }
      }
      component.setData(element, quickDocMessage, null, null, null);
      component.setToolwindowCallback(() -> {
        documentationManager.createToolWindow(element, extractOriginalElement(element));
        ToolWindow createdToolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.DOCUMENTATION);
        if (createdToolWindow != null) {
          createdToolWindow.setAutoHide(false);
        }
        AbstractPopup popup = popupBridge.getPopup();
        if (popup != null) {
          closePopup(popup);
        }
      });
      popupBridge.performWhenAvailable(component::setHint);
      EditorUtil.disposeWithEditor(editor, component);
      return component;
    }
  }

  private static PsiElement extractOriginalElement(PsiElement element) {
    if (element == null) return null;
    SmartPsiElementPointer originalElementPointer = element.getUserData(DocumentationManager.ORIGINAL_ELEMENT_KEY);
    return originalElementPointer == null ? null : originalElementPointer.getElement();
  }

  public static EditorMouseHoverPopupManager getInstance() {
    return ApplicationManager.getApplication().getComponent(EditorMouseHoverPopupManager.class);
  }

  @Nullable
  public DocumentationComponent getDocumentationComponent() {
    AbstractPopup hint = getCurrentHint();
    return hint == null ? null : UIUtil.findComponentOfType(hint.getComponent(), DocumentationComponent.class);
  }

  private static class PopupBridge {
    private AbstractPopup popup;
    private List<Consumer<AbstractPopup>> consumers = new ArrayList<>();

    private void setPopup(@NotNull AbstractPopup popup) {
      assert this.popup == null;
      this.popup = popup;
      consumers.forEach(c -> c.accept(popup));
      consumers = null;
    }

    @Nullable
    private AbstractPopup getPopup() {
      return popup;
    }

    private void performWhenAvailable(@NotNull Consumer<AbstractPopup> consumer) {
      if (popup == null) {
        consumers.add(consumer);
      }
      else {
        consumer.accept(popup);
      }
    }

    private void performOnCancel(@NotNull Runnable runnable) {
      performWhenAvailable(popup -> popup.addListener(new JBPopupListener() {
        @Override
        public void onClosed(@NotNull LightweightWindowEvent event) {
          runnable.run();
        }
      }));
    }
  }

  private static class WrapperPanel extends JPanel {
    private WrapperPanel(JComponent content) {
      super(new BorderLayout());
      setBorder(null);
      setContent(content);
    }

    private void setContent(JComponent content) {
      removeAll();
      add(content, BorderLayout.CENTER);
    }
  }

  private static class CombinedPopupLayout implements LayoutManager {
    private final JComponent highlightInfoComponent;
    private final DocumentationComponent quickDocComponent;

    private CombinedPopupLayout(JComponent highlightInfoComponent, DocumentationComponent quickDocComponent) {
      this.highlightInfoComponent = highlightInfoComponent;
      this.quickDocComponent = quickDocComponent;
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {}

    @Override
    public void removeLayoutComponent(Component comp) {}

    @Override
    public Dimension preferredLayoutSize(Container parent) {
      Dimension d1 = highlightInfoComponent == null ? new Dimension() : highlightInfoComponent.getPreferredSize();
      int w2 = quickDocComponent == null ? 0 : quickDocComponent.getPreferredWidth();
      int preferredWidth = Math.max(d1.width, w2);
      int h2 = quickDocComponent == null ? 0 : quickDocComponent.getPreferredHeight(preferredWidth);
      return new Dimension(preferredWidth, d1.height + h2);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
      Dimension d1 = highlightInfoComponent == null ? new Dimension() : highlightInfoComponent.getMinimumSize();
      Dimension d2 = quickDocComponent == null ? new Dimension() : quickDocComponent.getMinimumSize();
      return new Dimension(Math.max(d1.width, d2.width), d1.height + d2.height);
    }

    @Override
    public void layoutContainer(Container parent) {
      int width = parent.getWidth();
      int height = parent.getHeight();
      int h1 = highlightInfoComponent == null ? 0 : Math.min(height, highlightInfoComponent.getPreferredSize().height);
      if (highlightInfoComponent != null) {
        highlightInfoComponent.setBounds(0, 0, width, h1);
      }
      if (quickDocComponent != null) {
        quickDocComponent.setBounds(0, h1, width, height - h1);
      }
    }
  }
}

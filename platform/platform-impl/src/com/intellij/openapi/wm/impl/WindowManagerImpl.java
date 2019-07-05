// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.wm.impl;

import com.intellij.configurationStore.XmlSerializer;
import com.intellij.ide.DataManager;
import com.intellij.ide.RecentProjectsManagerBase;
import com.intellij.ide.impl.DataManagerImpl;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponentWithModificationTracker;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManagerListener;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeFrame;
import com.intellij.ui.JreHiDpiUtil;
import com.intellij.ui.ScreenUtil;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.EventDispatcher;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.UIUtil;
import com.sun.jna.platform.WindowUtils;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@State(
  name = "WindowManager",
  defaultStateAsResource = true,
  storages = {
    @Storage(value = "window.state.xml", roamingType = RoamingType.DISABLED),
    @Storage(value = "window.manager.xml", roamingType = RoamingType.DISABLED, deprecated = true),
  }
)
public final class WindowManagerImpl extends WindowManagerEx implements PersistentStateComponentWithModificationTracker<Element> {
  private static final Logger LOG = Logger.getInstance(WindowManagerImpl.class);

  @NonNls public static final String FULL_SCREEN = "ide.frame.full.screen";

  @NonNls private static final String FOCUSED_WINDOW_PROPERTY_NAME = "focusedWindow";
  @NonNls private static final String FRAME_ELEMENT = "frame";

  private Boolean myAlphaModeSupported;

  private final EventDispatcher<WindowManagerListener> myEventDispatcher = EventDispatcher.create(WindowManagerListener.class);

  private final CommandProcessor myCommandProcessor = new CommandProcessor();
  private final WindowWatcher myWindowWatcher = new WindowWatcher();
  /**
   * That is the default layout.
   */
  private final DesktopLayout myLayout = new DesktopLayout();

  // null keys must be supported
  // null key - root frame
  private final Map<Project, IdeFrameImpl> myProjectToFrame = new HashMap<>();

  private final Map<Project, Set<JDialog>> myDialogsToDispose = new THashMap<>();

  final FrameInfoHelper defaultFrameInfoHelper = new FrameInfoHelper();

  private final WindowAdapter myActivationListener = new WindowAdapter() {
    @Override
    public void windowActivated(WindowEvent e) {
      Window activeWindow = e.getWindow();
      // must be
      if (activeWindow instanceof IdeFrameImpl) {
        Project project = ((IdeFrameImpl)activeWindow).getProject();
        if (project != null) {
          proceedDialogDisposalQueue(project);
        }
      }
    }
  };

  private final ComponentAdapter myFrameStateListener = new ComponentAdapter() {
    @Override
    public void componentMoved(@NotNull ComponentEvent e) {
      IdeFrameImpl frame = (IdeFrameImpl)e.getComponent();
      Project project = frame.getProject();
      if (project == null) {
        // Component moved during project loading - update myDefaultFrameInfo directly.
        // Cannot mark as dirty and compute later, because to convert user space info to device space,
        // we need graphicsConfiguration, but we can get graphicsConfiguration only from frame,
        // but later, when getStateModificationCount or getState is called, may be no frame at all.
        defaultFrameInfoHelper.updateFrameInfo(frame);
      }
      else {
        ProjectFrameBounds.getInstance(project).markDirty();
      }
    }
  };

  public WindowManagerImpl() {
    DataManager dataManager = DataManager.getInstance();
    if (dataManager instanceof DataManagerImpl) {
      ((DataManagerImpl)dataManager).setWindowManager(this);
    }

    final Application application = ApplicationManager.getApplication();
    if (!application.isUnitTestMode()) {
      Disposer.register(application, this::disposeRootFrame);
    }

    final KeyboardFocusManager keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
    keyboardFocusManager.addPropertyChangeListener(FOCUSED_WINDOW_PROPERTY_NAME, myWindowWatcher);

    if (UIUtil.hasLeakingAppleListeners()) {
      UIUtil.addAwtListener(event -> {
        if (event.getID() == ContainerEvent.COMPONENT_ADDED) {
          if (((ContainerEvent)event).getChild() instanceof JViewport) {
            UIUtil.removeLeakingAppleListeners();
          }
        }
      }, AWTEvent.CONTAINER_EVENT_MASK, application);
    }
  }

  @Override
  @NotNull
  public IdeFrameImpl[] getAllProjectFrames() {
    final Collection<IdeFrameImpl> ideFrames = myProjectToFrame.values();
    return ideFrames.toArray(new IdeFrameImpl[0]);
  }

  @Override
  public JFrame findVisibleFrame() {
    IdeFrameImpl[] frames = getAllProjectFrames();
    return frames.length > 0 ? frames[0] : (JFrame)WelcomeFrame.getInstance();
  }

  @Override
  public void addListener(final WindowManagerListener listener) {
    myEventDispatcher.addListener(listener);
  }

  @Override
  public void removeListener(final WindowManagerListener listener) {
    myEventDispatcher.removeListener(listener);
  }

  @Override
  public final Rectangle getScreenBounds() {
    return ScreenUtil.getAllScreensRectangle();
  }

  @Override
  public Rectangle getScreenBounds(@NotNull Project project) {
    final GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
    final Point onScreen = getFrame(project).getLocationOnScreen();
    final GraphicsDevice[] devices = environment.getScreenDevices();
    for (final GraphicsDevice device : devices) {
      final Rectangle bounds = device.getDefaultConfiguration().getBounds();
      if (bounds.contains(onScreen)) {
        return bounds;
      }
    }

    return null;
  }

  @Override
  public final boolean isInsideScreenBounds(final int x, final int y, final int width) {
    return ScreenUtil.getAllScreensShape().contains(x, y, width, 1);
  }

  @Override
  public final boolean isAlphaModeSupported() {
    if (myAlphaModeSupported == null) {
      myAlphaModeSupported = calcAlphaModelSupported();
    }
    return myAlphaModeSupported.booleanValue();
  }

  private static boolean calcAlphaModelSupported() {
    if (AWTUtilitiesWrapper.isTranslucencyAPISupported()) {
      return AWTUtilitiesWrapper.isTranslucencySupported(AWTUtilitiesWrapper.TRANSLUCENT);
    }
    try {
      return WindowUtils.isWindowAlphaSupported();
    }
    catch (Throwable e) {
      return false;
    }
  }

  @Override
  public final void setAlphaModeRatio(final Window window, final float ratio) {
    if (!window.isDisplayable() || !window.isShowing()) {
      throw new IllegalArgumentException("window must be displayable and showing. window=" + window);
    }
    if (ratio < 0.0f || ratio > 1.0f) {
      throw new IllegalArgumentException("ratio must be in [0..1] range. ratio=" + ratio);
    }
    if (!isAlphaModeSupported() || !isAlphaModeEnabled(window)) {
      return;
    }


    setAlphaMode(window, ratio);
  }

  private static void setAlphaMode(Window window, float ratio) {
    try {
      if (SystemInfo.isMacOSLeopard) {
        if (window instanceof JWindow) {
          ((JWindow)window).getRootPane().putClientProperty("Window.alpha", 1.0f - ratio);
        } else if (window instanceof JDialog) {
          ((JDialog)window).getRootPane().putClientProperty("Window.alpha", 1.0f - ratio);
        } else if (window instanceof JFrame) {
          ((JFrame)window).getRootPane().putClientProperty("Window.alpha", 1.0f - ratio);
        }
      }
      else if (AWTUtilitiesWrapper.isTranslucencySupported(AWTUtilitiesWrapper.TRANSLUCENT)) {
        AWTUtilitiesWrapper.setWindowOpacity(window, 1.0f - ratio);
      }
      else {
        WindowUtils.setWindowAlpha(window, 1.0f - ratio);
      }
    }
    catch (Throwable e) {
      LOG.debug(e);
    }
  }

  @Override
  public void setWindowMask(final Window window, @Nullable final Shape mask) {
    try {
      if (AWTUtilitiesWrapper.isTranslucencySupported(AWTUtilitiesWrapper.PERPIXEL_TRANSPARENT)) {
        AWTUtilitiesWrapper.setWindowShape(window, mask);
      }
      else {
        WindowUtils.setWindowMask(window, mask);
      }
    }
    catch (Throwable e) {
      LOG.debug(e);
    }
  }

  @Override
  public void setWindowShadow(Window window, WindowShadowMode mode) {
    if (window instanceof JWindow) {
      JRootPane root = ((JWindow)window).getRootPane();
      root.putClientProperty("Window.shadow", mode == WindowShadowMode.DISABLED ? Boolean.FALSE : Boolean.TRUE);
      root.putClientProperty("Window.style", mode == WindowShadowMode.SMALL ? "small" : null);
    }
  }

  @Override
  public void resetWindow(final Window window) {
    try {
      if (!isAlphaModeSupported()) return;

      setWindowMask(window, null);
      setAlphaMode(window, 0f);
      setWindowShadow(window, WindowShadowMode.NORMAL);
    }
    catch (Throwable e) {
      LOG.debug(e);
    }
  }

  @Override
  public final boolean isAlphaModeEnabled(final Window window) {
    if (!window.isDisplayable() || !window.isShowing()) {
      throw new IllegalArgumentException("window must be displayable and showing. window=" + window);
    }
    return isAlphaModeSupported();
  }

  @Override
  public final void setAlphaModeEnabled(final Window window, final boolean state) {
    if (!window.isDisplayable() || !window.isShowing()) {
      throw new IllegalArgumentException("window must be displayable and showing. window=" + window);
    }
  }

  @Override
  public void hideDialog(JDialog dialog, Project project) {
    if (project == null) {
      dialog.dispose();
    }
    else {
      IdeFrameImpl frame = getFrame(project);
      if (frame.isActive()) {
        dialog.dispose();
      }
      else {
        queueForDisposal(dialog, project);
        dialog.setVisible(false);
      }
    }
  }

  @Override
  public void adjustContainerWindow(Component c, Dimension oldSize, Dimension newSize) {
    if (c == null) return;

    Window wnd = SwingUtilities.getWindowAncestor(c);

    if (wnd instanceof JWindow) {
      JBPopup popup = (JBPopup)((JWindow)wnd).getRootPane().getClientProperty(JBPopup.KEY);
      if (popup != null) {
        if (oldSize.height < newSize.height) {
          Dimension size = popup.getSize();
          size.height += newSize.height - oldSize.height;
          popup.setSize(size);
          popup.moveToFitScreen();
        }
      }
    }
  }

  @Override
  public final void doNotSuggestAsParent(final Window window) {
    myWindowWatcher.doNotSuggestAsParent(window);
  }

  @Override
  public final void dispatchComponentEvent(final ComponentEvent e) {
    myWindowWatcher.dispatchComponentEvent(e);
  }

  @Override
  @Nullable
  public final Window suggestParentWindow(@Nullable final Project project) {
    return myWindowWatcher.suggestParentWindow(project);
  }

  @Override
  public final StatusBar getStatusBar(final Project project) {
    IdeFrameImpl frame = myProjectToFrame.get(project);
    return frame == null ? null : frame.getStatusBar();
  }

  @Override
  public StatusBar getStatusBar(@NotNull Component c, @Nullable Project project) {
    Component parent = UIUtil.findUltimateParent(c);
    if (parent instanceof IdeFrame) {
      return ((IdeFrame)parent).getStatusBar().findChild(c);
    }

    IdeFrame frame = findFrameFor(project);
    if (frame != null) {
      return frame.getStatusBar().findChild(c);
    }

    assert false : "Cannot find status bar for " + c;

    return null;
  }

  @Override
  public IdeFrame findFrameFor(@Nullable final Project project) {
    IdeFrame frame = null;
    if (project != null) {
      frame = project.isDefault() ? WelcomeFrame.getInstance() : getFrame(project);
      if (frame == null) {
        frame = myProjectToFrame.get(null);
      }
    }
    else {
      Container eachParent = getMostRecentFocusedWindow();
      while(eachParent != null) {
        if (eachParent instanceof IdeFrame) {
          frame = (IdeFrame)eachParent;
          break;
        }
        eachParent = eachParent.getParent();
      }

      if (frame == null) {
        frame = tryToFindTheOnlyFrame();
      }
    }

    return frame;
  }

  private static IdeFrame tryToFindTheOnlyFrame() {
    IdeFrame candidate = null;
    final Frame[] all = Frame.getFrames();
    for (Frame each : all) {
      if (each instanceof IdeFrame) {
        if (candidate == null) {
          candidate = (IdeFrame)each;
        } else {
          candidate = null;
          break;
        }
      }
    }
    return candidate;
  }

  @Override
  public final IdeFrameImpl getFrame(@Nullable final Project project) {
    // no assert! otherwise WindowWatcher.suggestParentWindow fails for default project
    //LOG.assertTrue(myProject2Frame.containsKey(project));
    return myProjectToFrame.get(project);
  }

  @Override
  public IdeFrame getIdeFrame(@Nullable final Project project) {
    if (project != null) {
      return getFrame(project);
    }
    final Window window = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    final Component parent = UIUtil.findUltimateParent(window);
    if (parent instanceof IdeFrame) return (IdeFrame)parent;

    final Frame[] frames = Frame.getFrames();
    for (Frame each : frames) {
      if (each instanceof IdeFrame) {
        return (IdeFrame)each;
      }
    }

    return null;
  }

  @Nullable
  @ApiStatus.Internal
  public IdeFrameImpl getRootFrame() {
    return myProjectToFrame.get(null);
  }

  /**
   * This method is called when there is some opened project (IDE will not open Welcome Frame, but project).
   *
   * {@link IdeFrameImpl#init()} must be called explicitly.
   */
  @NotNull
  public IdeFrameImpl showFrame() {
    LOG.assertTrue(!myProjectToFrame.containsKey(null));

    IdeFrameImpl frame = new IdeFrameImpl();
    myProjectToFrame.put(null, frame);

    FrameInfo frameInfo = defaultFrameInfoHelper.getInfo();
    if (frameInfo != null && frameInfo.getBounds() != null) {
      // set bounds even if maximized because on unmaximize we must restore previous frame bounds
      frame.setBounds(validateFrameBounds(FrameBoundsConverter.convertFromDeviceSpace(frameInfo.getBounds())));
    }

    frame.setVisible(true);
    frame.setExtendedState(frameInfo.getExtendedState());
    frame.addComponentListener(myFrameStateListener);
    IdeMenuBar.installAppMenuIfNeeded(frame);
    return frame;
  }

  @NotNull
  private static Rectangle validateFrameBounds(@Nullable Rectangle frameBounds) {
    Rectangle bounds = frameBounds != null ? frameBounds.getBounds() : null;
    if (bounds == null || !ScreenUtil.isVisible(bounds)) {
      bounds = ScreenUtil.getMainScreenBounds();
      int xOff = bounds.width / 8;
      int yOff = bounds.height / 8;
      //noinspection UseDPIAwareInsets
      JBInsets.removeFrom(bounds, new Insets(yOff, xOff, yOff, xOff));
    }
    return bounds;
  }

  @Override
  public final IdeFrameImpl allocateFrame(@NotNull Project project) {
    LOG.assertTrue(!myProjectToFrame.containsKey(project));

    IdeFrameImpl frame = myProjectToFrame.remove(null);
    boolean isNewFrame = frame == null;
    FrameInfo frameInfo = null;
    if (isNewFrame) {
      frame = new IdeFrameImpl();
      frame.init();

      frameInfo = ProjectFrameBounds.getInstance(project).getFrameInfoInDeviceSpace();
      if (frameInfo == null || frameInfo.getBounds() == null) {
        IdeFrame lastFocusedFrame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
        Project lastFocusedProject = lastFocusedFrame == null ? null : lastFocusedFrame.getProject();
        if (lastFocusedProject != null) {
          frameInfo = ProjectFrameBounds.getInstance(lastFocusedProject).getActualFrameInfoInDeviceSpace(frame, this);
        }

        if (frameInfo == null || frameInfo.getBounds() == null) {
          frameInfo = defaultFrameInfoHelper.getInfo();
        }
      }

      if (frameInfo != null && frameInfo.getBounds() != null) {
        // update default frame info - newly opened project frame should be the same as last opened
        if (frameInfo != defaultFrameInfoHelper.getInfo()) {
          defaultFrameInfoHelper.copyFrom(frameInfo);
        }

        frame.setBounds(validateFrameBounds(FrameBoundsConverter.convertFromDeviceSpace(frameInfo.getBounds())));
      }
    }

    frame.setProject(project);
    myProjectToFrame.put(project, frame);

    if (isNewFrame) {
      frame.setVisible(true);
      if (frameInfo != null) {
        frame.setExtendedState(frameInfo.getExtendedState());
      }
    }

    frame.addWindowListener(myActivationListener);
    if (isNewFrame) {
      if (RecentProjectsManagerBase.getInstanceEx().isBatchOpening()) {
        frame.toBack();
      }
      frame.addComponentListener(myFrameStateListener);
    }
    myEventDispatcher.getMulticaster().frameCreated(frame);
    IdeMenuBar.installAppMenuIfNeeded(frame);

    return frame;
  }

  private void proceedDialogDisposalQueue(@NotNull Project project) {
    Set<JDialog> dialogs = myDialogsToDispose.remove(project);
    if (dialogs != null) {
      dialogs.forEach(Window::dispose);
    }
  }

  private void queueForDisposal(@NotNull JDialog dialog, @NotNull Project project) {
    myDialogsToDispose.computeIfAbsent(project, k -> new THashSet<>()).add(dialog);
  }

  @Override
  public final void releaseFrame(@NotNull final IdeFrameImpl frame) {
    myEventDispatcher.getMulticaster().beforeFrameReleased(frame);

    final Project project = frame.getProject();
    LOG.assertTrue(project != null);

    frame.removeWindowListener(myActivationListener);
    proceedDialogDisposalQueue(project);

    frame.setProject(null);
    frame.setTitle(null);
    frame.setFileTitle(null, null);

    myProjectToFrame.remove(project);
    if (myProjectToFrame.isEmpty()) {
      myProjectToFrame.put(null, frame);
    }
    else {
      StatusBar statusBar = frame.getStatusBar();
      if (statusBar != null) {
        Disposer.dispose(statusBar);
      }
      frame.dispose();
    }
  }

  public final void disposeRootFrame() {
    if (myProjectToFrame.size() == 1) {
      final IdeFrameImpl rootFrame = myProjectToFrame.remove(null);
      if (rootFrame != null) {
        // disposing last frame if quitting
        rootFrame.dispose();
      }
    }
  }

  @Override
  public final Window getMostRecentFocusedWindow() {
    return myWindowWatcher.getFocusedWindow();
  }

  @Override
  public final Component getFocusedComponent(@NotNull final Window window) {
    return myWindowWatcher.getFocusedComponent(window);
  }

  @Override
  @Nullable
  public final Component getFocusedComponent(@Nullable final Project project) {
    return myWindowWatcher.getFocusedComponent(project);
  }

  /**
   * Private part
   */
  @Override
  @NotNull
  public final CommandProcessor getCommandProcessor() {
    return myCommandProcessor;
  }

  @Override
  public void loadState(@NotNull Element state) {
    final Element frameElement = state.getChild(FRAME_ELEMENT);
    if (frameElement != null) {
      FrameInfo info = new FrameInfo();
      XmlSerializer.deserializeInto(frameElement, info);

      // backward compatibility - old name of extendedState attribute
      if (info.getExtendedState() == Frame.NORMAL) {
        String extendedState = frameElement.getAttributeValue("extended-state");
        if (extendedState != null) {
          info.setExtendedState(StringUtil.parseInt(extendedState, Frame.NORMAL));
        }
      }
      if ((info.getExtendedState() & Frame.ICONIFIED) > 0) {
        info.setExtendedState(Frame.NORMAL);
      }

      defaultFrameInfoHelper.copyFrom(info);
    }

    final Element desktopElement = state.getChild(DesktopLayout.TAG);
    if (desktopElement != null) {
      myLayout.readExternal(desktopElement);
    }
  }

  @Override
  public long getStateModificationCount() {
    return defaultFrameInfoHelper.getModificationCount() + myLayout.getStateModificationCount();
  }

  @Override
  public Element getState() {
    Element state = new Element("state");
    FrameInfo frameInfo = defaultFrameInfoHelper.getInfo();
    Element frameElement = frameInfo == null ? null : XmlSerializer.serialize(frameInfo);
    if (frameElement != null) {
      state.addContent(frameElement);
    }

    // save default layout
    Element layoutElement = myLayout.writeExternal(DesktopLayout.TAG);
    if (layoutElement != null) {
      state.addContent(layoutElement);
    }
    return state;
  }

  @Override
  public final DesktopLayout getLayout() {
    return myLayout;
  }

  @Override
  public final void setLayout(final DesktopLayout layout) {
    myLayout.copyFrom(layout);
  }

  public WindowWatcher getWindowWatcher() {
    return myWindowWatcher;
  }

  @Override
  public boolean isFullScreenSupportedInCurrentOS() {
    return FrameInfoHelper.isFullScreenSupportedInCurrentOS();
  }

  static boolean isFloatingMenuBarSupported() {
    return !SystemInfo.isMac && getInstance().isFullScreenSupportedInCurrentOS();
  }

  /**
   * Converts the frame bounds b/w the user space (JRE-managed HiDPI mode) and the device space (IDE-managed HiDPI mode).
   * See {@link JreHiDpiUtil#isJreHiDPIEnabled()}
   */
  static class FrameBoundsConverter {
    /**
     * @param bounds the bounds in the device space
     * @return the bounds in the user space
     */
    @NotNull
    static Rectangle convertFromDeviceSpace(@NotNull Rectangle bounds) {
      Rectangle b = bounds.getBounds();
      if (!shouldConvert()) return b;

      try {
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
          Rectangle devBounds = gd.getDefaultConfiguration().getBounds(); // in user space
          scaleUp(devBounds, gd.getDefaultConfiguration()); // to device space
          Rectangle2D.Float devBounds2D = new Rectangle2D.Float(devBounds.x, devBounds.y, devBounds.width, devBounds.height);
          Point2D.Float center2d = new Point2D.Float(b.x + b.width / 2, b.y + b.height / 2);
          if (devBounds2D.contains(center2d)) {
            scaleDown(b, gd.getDefaultConfiguration());
            break;
          }
        }
      }
      catch (HeadlessException ignore) {
      }
      return b;
    }

    /**
     * @param gc the graphics config
     * @param bounds the bounds in the user space
     * @return the bounds in the device space
     */
    public static Rectangle convertToDeviceSpace(GraphicsConfiguration gc, @NotNull Rectangle bounds) {
      Rectangle b = bounds.getBounds();
      if (!shouldConvert()) return b;

      try {
        scaleUp(b, gc);
      }
      catch (HeadlessException ignore) {
      }
      return b;
    }

    private static boolean shouldConvert() {
      if (SystemInfo.isLinux || // JRE-managed HiDPI mode is not yet implemented (pending)
          SystemInfo.isMac)     // JRE-managed HiDPI mode is permanent
      {
        return false;
      }
      // device space equals user space
      return JreHiDpiUtil.isJreHiDPIEnabled();
    }

    private static void scaleUp(@NotNull Rectangle bounds, @NotNull GraphicsConfiguration gc) {
      scale(bounds, gc.getBounds(), JBUIScale.sysScale(gc));
    }

    private static void scaleDown(@NotNull Rectangle bounds, @NotNull GraphicsConfiguration gc) {
      float scale = JBUIScale.sysScale(gc);
      assert scale != 0;
      scale(bounds, gc.getBounds(), 1 / scale);
    }

    private static void scale(@NotNull Rectangle bounds, @NotNull Rectangle deviceBounds, float scale) {
      // On Windows, JB SDK transforms the screen bounds to the user space as follows:
      // [x, y, width, height] -> [x, y, width / scale, height / scale]
      // xy are not transformed in order to avoid overlapping of the screen bounds in multi-dpi env.

      // scale the delta b/w xy and deviceBounds.xy
      int x = (int)Math.floor(deviceBounds.x + (bounds.x - deviceBounds.x) * scale);
      int y = (int)Math.floor(deviceBounds.y + (bounds.y - deviceBounds.y) * scale);

      bounds.setBounds(x, y, (int)Math.ceil(bounds.width * scale), (int)Math.ceil(bounds.height * scale));
    }
  }
}

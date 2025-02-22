// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.analysis;

import com.intellij.codeInsight.UnhandledExceptions;
import com.intellij.codeInsight.daemon.JavaErrorBundle;
import com.intellij.codeInsight.daemon.impl.DefaultHighlightUtil;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.HighlightVisitor;
import com.intellij.codeInsight.daemon.impl.quickfix.AdjustFunctionContextFix;
import com.intellij.codeInsight.daemon.impl.quickfix.QuickFixAction;
import com.intellij.codeInsight.intention.CommonIntentionAction;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.QuickFixFactory;
import com.intellij.codeInsight.quickfix.UnresolvedReferenceQuickFixUpdater;
import com.intellij.codeInspection.ex.GlobalInspectionContextBase;
import com.intellij.java.codeserver.highlighting.JavaErrorCollector;
import com.intellij.java.codeserver.highlighting.errors.JavaCompilationError;
import com.intellij.java.codeserver.highlighting.errors.JavaErrorHighlightType;
import com.intellij.java.codeserver.highlighting.errors.JavaErrorKinds;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.jvm.JvmModifier;
import com.intellij.lang.jvm.JvmModifiersOwner;
import com.intellij.lang.jvm.actions.JvmElementActionFactories;
import com.intellij.lang.jvm.actions.MemberRequestsKt;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.JavaVersionService;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.pom.java.JavaFeature;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.controlFlow.ControlFlowUtil;
import com.intellij.psi.impl.IncompleteModelUtil;
import com.intellij.psi.impl.source.resolve.JavaResolveUtil;
import com.intellij.psi.impl.source.resolve.graphInference.PsiPolyExpressionUtil;
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl;
import com.intellij.psi.infos.MethodCandidateInfo;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.*;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.NewUI;
import com.intellij.util.JavaPsiConstructorUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import com.siyeh.ig.psiutils.ClassUtils;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.intellij.psi.PsiModifier.SEALED;
import static com.intellij.util.ObjectUtils.tryCast;

// java highlighting: problems in java code like unresolved/incompatible symbols/methods etc.
public class HighlightVisitorImpl extends JavaElementVisitor implements HighlightVisitor {
  private final Map<String, Color> myTooltipColors = Map.of(
    "information", NewUI.isEnabled() ? JBUI.CurrentTheme.Editor.Tooltip.FOREGROUND : UIUtil.getToolTipForeground(),
    "grayed", UIUtil.getContextHelpForeground(),
    "error", NamedColorUtil.getErrorForeground()
  );
  private static final Pattern COLOR_CLASS = Pattern.compile("class=\"--java-display-(\\w+)\"");
  private @NotNull HighlightInfoHolder myHolder;
  private @NotNull LanguageLevel myLanguageLevel;
  private JavaSdkVersion myJavaSdkVersion;

  private @NotNull PsiFile myFile;
  private PsiJavaModule myJavaModule;
  private JavaErrorCollector myCollector;

  private PreviewFeatureUtil.PreviewFeatureVisitor myPreviewFeatureVisitor;

  // map codeBlock->List of PsiReferenceExpression of uninitialized final variables
  private final Map<PsiElement, Collection<PsiReferenceExpression>> myUninitializedVarProblems = new HashMap<>();
  // map codeBlock->List of PsiReferenceExpression of extra initialization of final variable
  private final Map<PsiElement, Collection<ControlFlowUtil.VariableInfo>> myFinalVarProblems = new HashMap<>();

  private final Map<String, Pair<PsiImportStaticReferenceElement, PsiClass>> mySingleImportedClasses = new HashMap<>();
  private final Map<String, Pair<PsiImportStaticReferenceElement, PsiField>> mySingleImportedFields = new HashMap<>();
  private final @NotNull Consumer<? super HighlightInfo.Builder> myErrorSink = builder -> add(builder);

  private final Set<PsiClass> myOverrideEquivalentMethodsVisitedClasses = new HashSet<>();
  // stored "clashing signatures" errors for the method (if the key is a PsiModifierList of the method), or the class (if the key is a PsiModifierList of the class)
  private final Map<PsiMember, HighlightInfo.Builder> myOverrideEquivalentMethodsErrors = new HashMap<>();
  private final Map<PsiMethod, PsiType> myExpectedReturnTypes = new HashMap<>();
  private final Function<? super PsiElement, ? extends PsiMethod> mySurroundingConstructor = entry -> findSurroundingConstructor(entry);
  private final Map<PsiElement, PsiMethod> myInsideConstructorOfClassCache = new HashMap<>(); // null value means "cached but no corresponding ctr found"
  private boolean myHasError; // true if myHolder.add() was called with HighlightInfo of >=ERROR severity. On each .visit(PsiElement) call this flag is reset. Useful to determine whether the error was already reported while visiting this PsiElement.

  private @NotNull PsiResolveHelper getResolveHelper() {
    return PsiResolveHelper.getInstance(getProject());
  }

  protected HighlightVisitorImpl() {
  }

  @Contract(pure = true)
  private boolean hasErrorResults() {
    return myHasError;
  }

  private @Contract(pure = true) @NotNull Project getProject() {
    return myHolder.getProject();
  }

  // element -> a constructor inside which this element is contained
  private PsiMethod findSurroundingConstructor(@NotNull PsiElement entry) {
    PsiMethod result = null;
    PsiElement element;
    for (element = entry; element != null && !(element instanceof PsiFile); element = element.getParent()) {
      result = myInsideConstructorOfClassCache.get(element);
      if (result != null || myInsideConstructorOfClassCache.containsKey(element)) {
        break;
      }
      if (element instanceof PsiMethod method && method.isConstructor()) {
        result = method;
        break;
      }
    }
    for (PsiElement e = entry; e != null && !(e instanceof PsiFile); e = e.getParent()) {
      myInsideConstructorOfClassCache.put(e, result);
      if (e == element) break;
    }
    return result;
  }

  /**
   * @deprecated use {@link #HighlightVisitorImpl()} and {@link #getResolveHelper()}
   */
  @Deprecated(forRemoval = true)
  protected HighlightVisitorImpl(@NotNull PsiResolveHelper psiResolveHelper) {
  }

  @Override
  @SuppressWarnings("MethodDoesntCallSuperMethod")
  public @NotNull HighlightVisitorImpl clone() {
    return new HighlightVisitorImpl();
  }

  @Override
  public boolean suitableForFile(@NotNull PsiFile file) {
    HighlightingLevelManager highlightingLevelManager = HighlightingLevelManager.getInstance(file.getProject());
    if (highlightingLevelManager.runEssentialHighlightingOnly(file)) {
      return false;
    }

    // both PsiJavaFile and PsiCodeFragment must match
    return file instanceof PsiImportHolder && !InjectedLanguageManager.getInstance(file.getProject()).isInjectedFragment(file);
  }

  @Override
  public void visit(@NotNull PsiElement element) {
    myHasError = false;
    element.accept(this);
  }

  @Override
  public boolean analyze(@NotNull PsiFile file, boolean updateWholeFile, @NotNull HighlightInfoHolder holder, @NotNull Runnable highlight) {
    try {
      prepare(holder, file);
      if (updateWholeFile) {
        GlobalInspectionContextBase.assertUnderDaemonProgress();
        Project project = file.getProject();
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        highlight.run();
        ProgressManager.checkCanceled();
        if (document != null) {
          new UnusedImportsVisitor(file, document).collectHighlights(holder);
        }
      }
      else {
        highlight.run();
      }
    }
    finally {
      myUninitializedVarProblems.clear();
      myFinalVarProblems.clear();
      mySingleImportedClasses.clear();
      mySingleImportedFields.clear();
      myJavaModule = null;
      myFile = null;
      myHolder = null;
      myCollector = null;
      myPreviewFeatureVisitor = null;
      myOverrideEquivalentMethodsVisitedClasses.clear();
      myOverrideEquivalentMethodsErrors.clear();
      myExpectedReturnTypes.clear();
      myInsideConstructorOfClassCache.clear();
    }

    return true;
  }

  protected void prepareToRunAsInspection(@NotNull HighlightInfoHolder holder) {
    prepare(holder, holder.getContextFile());
  }

  private void prepare(@NotNull HighlightInfoHolder holder, @NotNull PsiFile file) {
    myHolder = holder;
    myFile = file;
    myLanguageLevel = PsiUtil.getLanguageLevel(file);
    myJavaSdkVersion = ObjectUtils
      .notNull(JavaVersionService.getInstance().getJavaSdkVersion(file), JavaSdkVersion.fromLanguageLevel(myLanguageLevel));
    myJavaModule = JavaFeature.MODULES.isSufficient(myLanguageLevel) ? JavaModuleGraphUtil.findDescriptorByElement(file) : null;
    myPreviewFeatureVisitor = myLanguageLevel.isPreview() ? null : new PreviewFeatureUtil.PreviewFeatureVisitor(myLanguageLevel, myErrorSink);
    JavaErrorFixProvider errorFixProvider = JavaErrorFixProvider.getInstance();
    myCollector = new JavaErrorCollector(myFile, myJavaModule, error -> reportError(error, errorFixProvider));
  }

  private void reportError(JavaCompilationError<?, ?> error, JavaErrorFixProvider errorFixProvider) {
    JavaErrorHighlightType javaHighlightType = error.highlightType();
    HighlightInfoType type = switch (javaHighlightType) {
      case ERROR, FILE_LEVEL_ERROR -> HighlightInfoType.ERROR;
      case UNHANDLED_EXCEPTION -> HighlightInfoType.UNHANDLED_EXCEPTION;
      case WRONG_REF -> HighlightInfoType.WRONG_REF;
    };
    TextRange range = error.range();
    HtmlChunk tooltip = error.tooltip();
    HighlightInfo.Builder info = HighlightInfo.newHighlightInfo(type);
    if (tooltip.isEmpty()) {
      info.descriptionAndTooltip(error.description().toString());
    } else {
      info.description(error.description().toString()).escapedToolTip(postProcessClasses(tooltip.toString()));
    }
    if (javaHighlightType == JavaErrorHighlightType.FILE_LEVEL_ERROR) {
      info.fileLevelAnnotation();
    }
    PsiElement anchor = error.anchor();
    if (range != null) {
      info.range(anchor, range);
      if (range.getLength() == 0) {
        int offset = range.getStartOffset() + anchor.getTextRange().getStartOffset();
        CharSequence sequence = myFile.getFileDocument().getCharsSequence();
        if (offset >= sequence.length() || sequence.charAt(offset) == '\n') {
          info.endOfLine();
        }
      }
    } else {
      info.range(anchor);
    }
    for (CommonIntentionAction fix : errorFixProvider.getFixes(error)) {
      info.registerFix(fix.asIntention(), null, null, null, null);
    }
    if (error.kind() == JavaErrorKinds.EXPRESSION_EXPECTED) {
      UnresolvedReferenceQuickFixUpdater.getInstance(getProject()).registerQuickFixesLater((PsiReference)error.psi(), info);
    }
    add(info);
  }

  private @NlsContexts.Tooltip @NotNull String postProcessClasses(@NlsSafe @NotNull String html) {
    Matcher matcher = COLOR_CLASS.matcher(html);
    @NlsSafe StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      Color color = myTooltipColors.get(matcher.group(1));
      if (color != null) {
        matcher.appendReplacement(result, "style=\"color:" + ColorUtil.toHtmlColor(color) + ";\"");
      } else {
        matcher.appendReplacement(result, matcher.group());
      }
    }
    matcher.appendTail(result);
    return result.toString();
  }

  @Override
  public void visitElement(@NotNull PsiElement element) {
    myCollector.processElement(element);
    if (!(myFile instanceof ServerPageFile)) {
      add(DefaultHighlightUtil.checkUnicodeBadCharacter(element));
    }
  }

  public static @Nullable JavaResolveResult resolveJavaReference(@NotNull PsiReference reference) {
    return reference instanceof PsiJavaReference psiJavaReference ? psiJavaReference.advancedResolve(false) : null;
  }

  private boolean add(@Nullable HighlightInfo.Builder builder) {
    if (builder != null) {
      HighlightInfo info = builder.create();
      if (info != null && info.getSeverity().compareTo(HighlightSeverity.ERROR) >= 0) {
        myHasError = true;
      }
      return myHolder.add(info);
    }
    return false;
  }

  @Override
  public void visitArrayInitializerExpression(@NotNull PsiArrayInitializerExpression expression) {
    super.visitArrayInitializerExpression(expression);
    if (!(expression.getParent() instanceof PsiNewExpression)) {
      if (!hasErrorResults()) add(GenericsHighlightUtil.checkGenericArrayCreation(expression, expression.getType()));
    }
  }

  @Override
  public void visitAssignmentExpression(@NotNull PsiAssignmentExpression assignment) {
    if (!hasErrorResults()) add(HighlightUtil.checkAssignmentCompatibleTypes(assignment));
    if (!hasErrorResults()) add(HighlightUtil.checkAssignmentOperatorApplicable(assignment));
    if (!hasErrorResults()) add(HighlightUtil.checkOutsideDeclaredCantBeAssignmentInGuard(assignment.getLExpression()));
    if (!hasErrorResults()) visitExpression(assignment);
  }

  @Override
  public void visitPolyadicExpression(@NotNull PsiPolyadicExpression expression) {
    super.visitPolyadicExpression(expression);
    if (!hasErrorResults()) add(HighlightUtil.checkPolyadicOperatorApplicable(expression));
  }

  @Override
  public void visitLambdaExpression(@NotNull PsiLambdaExpression expression) {
    add(checkFeature(expression, JavaFeature.LAMBDA_EXPRESSIONS));
    PsiElement parent = PsiUtil.skipParenthesizedExprUp(expression.getParent());
    if (toReportFunctionalExpressionProblemOnParent(parent)) return;
    if (!hasErrorResults() && !LambdaUtil.isValidLambdaContext(parent)) {
      add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression)
            .descriptionAndTooltip(JavaErrorBundle.message("lambda.expression.not.expected")));
    }

    if (!hasErrorResults()) add(LambdaHighlightingUtil.checkConsistentParameterDeclaration(expression));

    PsiType functionalInterfaceType = null;
    if (!hasErrorResults()) {
      functionalInterfaceType = expression.getFunctionalInterfaceType();
      if (functionalInterfaceType != null) {
        add(HighlightClassUtil.checkExtendsSealedClass(expression, functionalInterfaceType));
        if (!hasErrorResults()) {
          String notFunctionalMessage = LambdaHighlightingUtil.checkInterfaceFunctional(expression, functionalInterfaceType);
          if (notFunctionalMessage != null) {
            add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression)
                  .descriptionAndTooltip(notFunctionalMessage));
          }
          else {
            add(LambdaHighlightingUtil.checkFunctionalInterfaceTypeAccessible(myFile.getProject(), expression, functionalInterfaceType));
          }
        }
      }
      else if (LambdaUtil.getFunctionalInterfaceType(expression, true) != null) {
        add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(
          JavaErrorBundle.message("cannot.infer.functional.interface.type")));
      }
    }

    if (!hasErrorResults() && functionalInterfaceType != null) {
      PsiCallExpression callExpression = parent instanceof PsiExpressionList && parent.getParent() instanceof PsiCallExpression ?
                                         (PsiCallExpression)parent.getParent() : null;
      MethodCandidateInfo parentCallResolveResult =
        callExpression != null ? tryCast(callExpression.resolveMethodGenerics(), MethodCandidateInfo.class) : null;
      String parentInferenceErrorMessage = parentCallResolveResult != null ? parentCallResolveResult.getInferenceErrorMessage() : null;
      PsiType returnType = LambdaUtil.getFunctionalInterfaceReturnType(functionalInterfaceType);
      Map<PsiElement, @Nls String> returnErrors = null;
      Set<PsiTypeParameter> parentTypeParameters =
        parentCallResolveResult == null ? Set.of() : Set.of(parentCallResolveResult.getElement().getTypeParameters());
      // If return type of the lambda was not fully inferred and lambda parameters don't mention the same type,
      // it means that lambda is not responsible for inference failure and blaming it would be unreasonable.
      boolean skipReturnCompatibility = parentCallResolveResult != null &&
                                        PsiTypesUtil.mentionsTypeParameters(returnType, parentTypeParameters)
                                        && !LambdaHighlightingUtil.lambdaParametersMentionTypeParameter(functionalInterfaceType, parentTypeParameters);
      if (!skipReturnCompatibility) {
        returnErrors = LambdaUtil.checkReturnTypeCompatible(expression, returnType);
      }
      if (parentInferenceErrorMessage != null && (returnErrors == null || !returnErrors.containsValue(parentInferenceErrorMessage))) {
        if (returnErrors == null) return;
        HighlightInfo.Builder info =
          HighlightMethodUtil.createIncompatibleTypeHighlightInfo(callExpression, getResolveHelper(),
                                                                  parentCallResolveResult, expression);
        if (info != null) {
          for (PsiElement errorElement : returnErrors.keySet()) {
            IntentionAction action = AdjustFunctionContextFix.createFix(errorElement);
            if (action != null) {
              info.registerFix(action, null, null, null, null);
            }
          }
          add(info);
        }
      }
      else if (returnErrors != null && !PsiTreeUtil.hasErrorElements(expression)) {
        for (Map.Entry<PsiElement, @Nls String> entry : returnErrors.entrySet()) {
          PsiElement element = entry.getKey();
          HighlightInfo.Builder info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
            .range(element)
            .descriptionAndTooltip(entry.getValue());
          IntentionAction action = AdjustFunctionContextFix.createFix(element);
          if (action != null) {
            info.registerFix(action, null, null, null, null);
          }
          if (element instanceof PsiExpression expr) {
            HighlightFixUtil.registerLambdaReturnTypeFixes(HighlightUtil.asConsumer(info), expression, expr);
          }
          add(info);
        }
      }
    }

    if (!hasErrorResults() && functionalInterfaceType != null) {
      PsiClassType.ClassResolveResult resolveResult = PsiUtil.resolveGenericsClassInType(functionalInterfaceType);
      PsiMethod interfaceMethod = LambdaUtil.getFunctionalInterfaceMethod(resolveResult);
      if (interfaceMethod != null) {
        PsiParameter[] parameters = interfaceMethod.getParameterList().getParameters();
        add(LambdaHighlightingUtil.checkParametersCompatible(expression, parameters,
                                                             LambdaUtil.getSubstitutor(interfaceMethod, resolveResult)));
      }
    }

    if (!hasErrorResults()) {
      PsiElement body = expression.getBody();
      if (body instanceof PsiCodeBlock block) {
        add(HighlightControlFlowUtil.checkUnreachableStatement(block));
      }
    }
  }

  @Override
  public void visitBreakStatement(@NotNull PsiBreakStatement statement) {
    super.visitBreakStatement(statement);
    if (!hasErrorResults()) add(HighlightUtil.checkBreakTarget(statement, myLanguageLevel));
  }

  @Override
  public void visitYieldStatement(@NotNull PsiYieldStatement statement) {
    super.visitYieldStatement(statement);
    if (!hasErrorResults()) add(HighlightUtil.checkYieldOutsideSwitchExpression(statement));
    if (!hasErrorResults()) {
      PsiExpression expression = statement.getExpression();
      if (expression != null) {
        add(HighlightUtil.checkYieldExpressionType(expression));
      }
    }
  }

  @Override
  public void visitExpressionStatement(@NotNull PsiExpressionStatement statement) {
    super.visitExpressionStatement(statement);
    PsiElement parent = statement.getParent();
    if (parent instanceof PsiSwitchLabeledRuleStatement ruleStatement) {
      PsiSwitchBlock switchBlock = ruleStatement.getEnclosingSwitchBlock();
      if (switchBlock instanceof PsiSwitchExpression expr && !PsiPolyExpressionUtil.isPolyExpression(expr)) {
        add(HighlightUtil.checkYieldExpressionType(statement.getExpression()));
      }
    }
  }

  @Override
  public void visitClass(@NotNull PsiClass aClass) {
    super.visitClass(aClass);
    if (aClass instanceof PsiSyntheticClass) return;
    if (!hasErrorResults()) GenericsHighlightUtil.checkTypeParameterOverrideEquivalentMethods(aClass, myLanguageLevel, myErrorSink, myOverrideEquivalentMethodsVisitedClasses, myOverrideEquivalentMethodsErrors);
  }

  @Override
  public void visitClassInitializer(@NotNull PsiClassInitializer initializer) {
    super.visitClassInitializer(initializer);
    if (!hasErrorResults()) add(HighlightControlFlowUtil.checkInitializerCompleteNormally(initializer));
    if (!hasErrorResults()) add(HighlightControlFlowUtil.checkUnreachableStatement(initializer.getBody()));
  }

  @Override
  public void visitClassObjectAccessExpression(@NotNull PsiClassObjectAccessExpression expression) {
    super.visitClassObjectAccessExpression(expression);
    if (!hasErrorResults()) add(GenericsHighlightUtil.checkClassObjectAccessExpression(expression));
  }

  @Override
  public void visitContinueStatement(@NotNull PsiContinueStatement statement) {
    super.visitContinueStatement(statement);
    if (!hasErrorResults()) add(HighlightUtil.checkContinueTarget(statement, myLanguageLevel));
  }

  @Override
  public void visitJavaToken(@NotNull PsiJavaToken token) {
    super.visitJavaToken(token);

    IElementType type = token.getTokenType();
    if (!hasErrorResults() && type == JavaTokenType.TEXT_BLOCK_LITERAL) {
      add(checkFeature(token, JavaFeature.TEXT_BLOCKS));
    }

    if (!hasErrorResults() && type == JavaTokenType.RBRACE && token.getParent() instanceof PsiCodeBlock) {
      PsiElement gParent = token.getParent().getParent();
      PsiCodeBlock codeBlock;
      PsiType returnType;
      if (gParent instanceof PsiMethod method) {
        codeBlock = method.getBody();
        returnType = method.getReturnType();
      }
      else if (gParent instanceof PsiLambdaExpression lambdaExpression) {
        PsiElement body = lambdaExpression.getBody();
        if (!(body instanceof PsiCodeBlock)) return;
        codeBlock = (PsiCodeBlock)body;
        returnType = LambdaUtil.getFunctionalInterfaceReturnType(lambdaExpression);
      }
      else {
        return;
      }
      add(HighlightControlFlowUtil.checkMissingReturnStatement(codeBlock, returnType));
    }

    if (!hasErrorResults()) {
      add(HighlightUtil.checkExtraSemicolonBetweenImportStatements(token, type, myLanguageLevel));
    }
  }

  @Override
  public void visitEnumConstant(@NotNull PsiEnumConstant enumConstant) {
    super.visitEnumConstant(enumConstant);
    if (!hasErrorResults()) {
      PsiClass containingClass = Objects.requireNonNull(enumConstant.getContainingClass());
      PsiClassType type = JavaPsiFacade.getElementFactory(getProject()).createType(containingClass);
      HighlightMethodUtil.checkConstructorCall(getProject(), type.resolveGenerics(), enumConstant, type, null, myJavaSdkVersion,
                                               enumConstant.getArgumentList(), myErrorSink);
    }
  }

  @Override
  public void visitExpression(@NotNull PsiExpression expression) {
    ProgressManager.checkCanceled(); // visitLiteralExpression is invoked very often in array initializers
    super.visitExpression(expression);

    PsiElement parent = expression.getParent();
    // Method expression of the call should not be especially processed
    if (parent instanceof PsiMethodCallExpression) return;
    PsiType type = expression.getType();

    if (!hasErrorResults() && parent instanceof PsiNewExpression newExpression &&
        newExpression.getQualifier() != expression && newExpression.getArrayInitializer() != expression) {
      add(HighlightUtil.checkAssignability(PsiTypes.intType(), expression.getType(), expression, expression));  // like in 'new String["s"]'
    }
    if (!hasErrorResults()) add(HighlightControlFlowUtil.checkCannotWriteToFinal(expression, myFile));
    if (!hasErrorResults()) add(HighlightUtil.checkVariableExpected(expression));
    if (!hasErrorResults()) add(HighlightUtil.checkConditionalExpressionBranchTypesMatch(expression, type));
    if (!hasErrorResults() &&
        parent instanceof PsiThrowStatement statement &&
        statement.getException() == expression &&
        type != null) {
      add(HighlightUtil.checkMustBeThrowable(type, expression, true));
    }
    if (!hasErrorResults() && shouldReportForeachNotApplicable(expression)) {
      add(GenericsHighlightUtil.checkForeachExpressionTypeIsIterable(expression));
    }
  }

  private static boolean shouldReportForeachNotApplicable(@NotNull PsiExpression expression) {
    if (!(expression.getParent() instanceof PsiForeachStatementBase parentForEach)) return false;

    PsiExpression iteratedValue = parentForEach.getIteratedValue();
    if (iteratedValue != expression) return false;

    // Ignore if the type of the value which is being iterated over is not resolved yet
    PsiType iteratedValueType = iteratedValue.getType();
    return iteratedValueType == null || !PsiTypesUtil.hasUnresolvedComponents(iteratedValueType);
  }

  @Override
  public void visitExpressionList(@NotNull PsiExpressionList list) {
    super.visitExpressionList(list);
    PsiElement parent = list.getParent();
    if (parent instanceof PsiMethodCallExpression expression && expression.getArgumentList() == list) {
      PsiReferenceExpression referenceExpression = expression.getMethodExpression();
      JavaResolveResult[] results = resolveOptimised(referenceExpression);
      if (results == null) return;
      JavaResolveResult result = results.length == 1 ? results[0] : JavaResolveResult.EMPTY;

      PsiResolveHelper resolveHelper;
      if ((!result.isAccessible() || !result.isStaticsScopeCorrect()) &&
          !HighlightMethodUtil.isDummyConstructorCall(expression, resolveHelper = getResolveHelper(), list, referenceExpression) &&
          // this check is for fake expression from JspMethodCallImpl
          referenceExpression.getParent() == expression) {
        try {
          if (PsiTreeUtil.findChildrenOfType(expression.getArgumentList(), PsiLambdaExpression.class).isEmpty()) {
            PsiElement resolved = result.getElement();
            add(HighlightMethodUtil.checkAmbiguousMethodCallArguments(referenceExpression, results, list, resolved, result, expression,
                                                                      resolveHelper, list));
          }
        }
        catch (IndexNotReadyException ignored) {
        }
      }
    }
  }

  @Override
  public void visitField(@NotNull PsiField field) {
    super.visitField(field);
    if (!hasErrorResults()) add(HighlightControlFlowUtil.checkFinalFieldInitialized(field));
  }

  @Override
  public void visitForStatement(@NotNull PsiForStatement statement) {
    add(HighlightUtil.checkForStatement(statement));
  }

  @Override
  public void visitImportStaticStatement(@NotNull PsiImportStaticStatement statement) {
    visitElement(statement);
    if (!hasErrorResults()) add(ImportsHighlightUtil.checkStaticOnDemandImportResolvesToClass(statement));
    if (!hasErrorResults()) PreviewFeatureUtil.checkPreviewFeature(statement, myPreviewFeatureVisitor);
  }

  @Override
  public void visitIdentifier(@NotNull PsiIdentifier identifier) {
    PsiElement parent = identifier.getParent();
    if (parent instanceof PsiVariable variable) {
      add(HighlightUtil.checkVariableAlreadyDefined(variable));
      if (variable.isUnnamed()) {
        HighlightInfo.Builder notAvailable = checkFeature(variable, JavaFeature.UNNAMED_PATTERNS_AND_VARIABLES);
        if (notAvailable != null) {
          add(notAvailable);
        } else {
          add(HighlightUtil.checkUnnamedVariableDeclaration(variable));
        }
      }
    }
    else if (parent instanceof PsiClass aClass) {
      if (!hasErrorResults() && JavaFeature.EXTENSION_METHODS.isSufficient(myLanguageLevel)) {
        add(GenericsHighlightUtil.checkUnrelatedDefaultMethods(aClass, identifier));
      }

      if (!hasErrorResults()) {
        add(GenericsHighlightUtil.checkUnrelatedConcrete(aClass, identifier));
      }
    }
    else if (parent instanceof PsiMethod method) {
      if (method.isConstructor()) {
        HighlightInfo.Builder info = HighlightMethodUtil.checkConstructorName(method);
        if (info != null) {
          PsiType expectedType = myExpectedReturnTypes.computeIfAbsent(method, HighlightMethodUtil::determineReturnType);
          if (expectedType != null) {
            HighlightUtil.registerReturnTypeFixes(info, method, expectedType);
          }
        }
        add(info);
      }
      PsiClass aClass = method.getContainingClass();
      if (aClass != null) {
        add(GenericsHighlightUtil.checkDefaultMethodOverridesMemberOfJavaLangObject(myLanguageLevel, aClass, method, identifier));
      }
    }

    add(HighlightUtil.checkUnderscore(identifier, myLanguageLevel));

    super.visitIdentifier(identifier);
  }

  @Override
  public void visitImportStatement(@NotNull PsiImportStatement statement) {
    if (!hasErrorResults()) {
      add(HighlightUtil.checkSingleImportClassConflict(statement, mySingleImportedClasses, myFile));
    }
    if (!hasErrorResults()) {
      PreviewFeatureUtil.checkPreviewFeature(statement, myPreviewFeatureVisitor);
    }
  }

  @Override
  public void visitImportStaticReferenceElement(@NotNull PsiImportStaticReferenceElement ref) {
    String refName = ref.getReferenceName();
    JavaResolveResult[] results = ref.multiResolve(false);

    PsiElement referenceNameElement = ref.getReferenceNameElement();
    if (results.length == 0) {
      assert referenceNameElement != null : ref;
      if (IncompleteModelUtil.isIncompleteModel(ref) && ref.getClassReference().resolve() == null) {
        add(HighlightUtil.getPendingReferenceHighlightInfo(referenceNameElement));
      } else {
        String description = JavaErrorBundle.message("cannot.resolve.symbol", refName);
        HighlightInfo.Builder info =
          HighlightInfo.newHighlightInfo(HighlightInfoType.WRONG_REF).range(referenceNameElement).descriptionAndTooltip(description);
        add(info);
      }
    }
    else {
      PsiManager manager = ref.getManager();
      for (JavaResolveResult result : results) {
        PsiElement element = result.getElement();

        String description = null;
        if (element instanceof PsiClass) {
          Pair<PsiImportStaticReferenceElement, PsiClass> imported = mySingleImportedClasses.get(refName);
          PsiClass aClass = Pair.getSecond(imported);
          if (aClass != null && !manager.areElementsEquivalent(aClass, element)) {
            description = imported.first == null
                          ? JavaErrorBundle.message("single.import.class.conflict", refName)
                          : imported.first.equals(ref)
                            ? JavaErrorBundle.message("class.is.ambiguous.in.single.static.import", refName)
                            : JavaErrorBundle.message("class.is.already.defined.in.single.static.import", refName);
          }
          mySingleImportedClasses.put(refName, Pair.create(ref, (PsiClass)element));
        }
        else if (element instanceof PsiField) {
          Pair<PsiImportStaticReferenceElement, PsiField> imported = mySingleImportedFields.get(refName);
          PsiField field = Pair.getSecond(imported);
          if (field != null && !manager.areElementsEquivalent(field, element)) {
            description = imported.first.equals(ref)
                          ? JavaErrorBundle.message("field.is.ambiguous.in.single.static.import", refName)
                          : JavaErrorBundle.message("field.is.already.defined.in.single.static.import", refName);
          }
          mySingleImportedFields.put(refName, Pair.create(ref, (PsiField)element));
        }

        if (description != null) {
          add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(ref).descriptionAndTooltip(description));
        }
      }
    }
    if (!hasErrorResults() && results.length == 1) {
      add(HighlightUtil.checkReference(ref, results[0], myFile, myLanguageLevel));
      if (!hasErrorResults()) {
        PsiElement element = results[0].getElement();
        PsiClass containingClass = element instanceof PsiMethod psiMethod ? psiMethod.getContainingClass() : null;
        if (containingClass != null && containingClass.isInterface()) {
          add(
            HighlightMethodUtil.checkStaticInterfaceCallQualifier(ref, results[0], ObjectUtils.notNull(ref.getReferenceNameElement(), ref),
                                                                  containingClass));
        }
      }
    }
  }

  @Override
  public void visitInstanceOfExpression(@NotNull PsiInstanceOfExpression expression) {
    super.visitInstanceOfExpression(expression);
    if (!hasErrorResults()) HighlightUtil.checkInstanceOfApplicable(expression, myErrorSink);
    if (!hasErrorResults()) add(GenericsHighlightUtil.checkInstanceOfGenericType(myLanguageLevel, expression));
    if (!hasErrorResults() &&
        JavaFeature.PATTERNS.isSufficient(myLanguageLevel) &&
        // 5.20.2 Removed restriction on pattern instanceof for unconditional patterns (JEP 432, 440)
        !JavaFeature.PATTERN_GUARDS_AND_RECORD_PATTERNS.isSufficient(myLanguageLevel)) {
      add(HighlightUtil.checkInstanceOfPatternSupertype(expression));
    }
  }

  @Override
  public void visitImportModuleStatement(@NotNull PsiImportModuleStatement statement) {
    super.visitImportModuleStatement(statement);
    if (!hasErrorResults()) add(checkFeature(statement, JavaFeature.MODULE_IMPORT_DECLARATIONS));
    if (!hasErrorResults()) add(ModuleHighlightUtil.checkModuleReference(statement));
  }

  @Override
  public void visitKeyword(@NotNull PsiKeyword keyword) {
    super.visitKeyword(keyword);
    PsiElement parent = keyword.getParent();
    String text = keyword.getText();
    if (parent instanceof PsiModifierList psiModifierList) {
      PsiElement pParent = psiModifierList.getParent();
      if (PsiModifier.ABSTRACT.equals(text) && pParent instanceof PsiMethod psiMethod) {
        if (!hasErrorResults()) {
          add(HighlightMethodUtil.checkAbstractMethodInConcreteClass(psiMethod, keyword));
        }
      }
      else if (pParent instanceof PsiEnumConstant) {
        String description = JavaErrorBundle.message("modifiers.for.enum.constants");
        add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(keyword).descriptionAndTooltip(description));
      }
    }
  }

  @Override
  public void visitMethod(@NotNull PsiMethod method) {
    super.visitMethod(method);
    if (!hasErrorResults()) add(HighlightControlFlowUtil.checkUnreachableStatement(method.getBody()));
    if (!hasErrorResults()) add(HighlightMethodUtil.checkConstructorHandleSuperClassExceptions(method));
    if (!hasErrorResults()) add(HighlightMethodUtil.checkRecordAccessorDeclaration(method));
    if (!hasErrorResults()) HighlightMethodUtil.checkRecordConstructorDeclaration(method, myErrorSink);
  }

  @Override
  public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
    visitElement(expression);
    if (!hasErrorResults()) {
      try {
        HighlightMethodUtil.checkMethodCall(expression, getResolveHelper(), myLanguageLevel, myJavaSdkVersion, myFile,
                                            myErrorSink);
      }
      catch (IndexNotReadyException ignored) {
      }
    }

    if (!hasErrorResults()) add(HighlightMethodUtil.checkConstructorCallProblems(expression));
    if (!hasErrorResults()) add(HighlightMethodUtil.checkSuperAbstractMethodDirectCall(expression));

    if (!hasErrorResults()) visitExpression(expression);
    if (!hasErrorResults()) {
      PreviewFeatureUtil.checkPreviewFeature(expression, myPreviewFeatureVisitor);
    }
  }

  @Override
  public void visitModifierList(@NotNull PsiModifierList list) {
    super.visitModifierList(list);
    PsiElement parent = list.getParent();
    if (parent instanceof PsiMethod method) {
      PsiClass aClass = method.getContainingClass();
      if (!hasErrorResults() && aClass != null) {
        GenericsHighlightUtil.computeOverrideEquivalentMethodErrors(aClass, myOverrideEquivalentMethodsVisitedClasses, myOverrideEquivalentMethodsErrors);
        myErrorSink.accept(myOverrideEquivalentMethodsErrors.get(method));
      }
    }
    else if (parent instanceof PsiClass aClass) {
      if (!hasErrorResults()) {
        GenericsHighlightUtil.computeOverrideEquivalentMethodErrors(aClass, myOverrideEquivalentMethodsVisitedClasses, myOverrideEquivalentMethodsErrors);
        myErrorSink.accept(myOverrideEquivalentMethodsErrors.get(aClass));
      }
    }
  }

  @Override
  public void visitNewExpression(@NotNull PsiNewExpression expression) {
    PsiType type = expression.getType();
    if (!hasErrorResults()) add(GenericsHighlightUtil.checkTypeParameterInstantiation(expression));
    if (!hasErrorResults()) add(GenericsHighlightUtil.checkGenericArrayCreation(expression, type));
    try {
      if (!hasErrorResults()) HighlightMethodUtil.checkNewExpression(getProject(), expression, type, myJavaSdkVersion, myErrorSink);
    }
    catch (IndexNotReadyException ignored) {
    }

    if (!hasErrorResults()) visitExpression(expression);

    if (!hasErrorResults()) {
      PreviewFeatureUtil.checkPreviewFeature(expression, myPreviewFeatureVisitor);
    }
  }

  @Override
  public void visitPackageStatement(@NotNull PsiPackageStatement statement) {
    super.visitPackageStatement(statement);
    if (JavaFeature.MODULES.isSufficient(myLanguageLevel)) {
      if (!hasErrorResults()) add(ModuleHighlightUtil.checkPackageStatement(statement, myFile, myJavaModule));
    }
  }

  @Override
  public void visitRecordComponent(@NotNull PsiRecordComponent recordComponent) {
    super.visitRecordComponent(recordComponent);
    if (!hasErrorResults()) add(HighlightControlFlowUtil.checkRecordComponentInitialized(recordComponent));
  }

  @Override
  public void visitParameter(@NotNull PsiParameter parameter) {
    super.visitParameter(parameter);

    PsiElement parent = parameter.getParent();
    if (parent instanceof PsiCatchSection) {
      if (!hasErrorResults()) add(HighlightUtil.checkCatchParameterIsThrowable(parameter));
      if (!hasErrorResults()) GenericsHighlightUtil.checkCatchParameterIsClass(parameter, myErrorSink);
    }
  }

  @Override
  public void visitParameterList(@NotNull PsiParameterList list) {
    super.visitParameterList(list);
    if (!hasErrorResults()) add(HighlightUtil.checkAnnotationMethodParameters(list));
  }

  @Override
  public void visitUnaryExpression(@NotNull PsiUnaryExpression expression) {
    super.visitUnaryExpression(expression);
    if (!hasErrorResults()) {
      add(HighlightUtil.checkUnaryOperatorApplicable(expression.getOperationSign(), expression.getOperand()));
    }
    if (!hasErrorResults()) add(HighlightUtil.checkOutsideDeclaredCantBeAssignmentInGuard(expression.getOperand()));
  }

  @Override
  public void visitReferenceElement(@NotNull PsiJavaCodeReferenceElement ref) {
    super.visitReferenceElement(ref);
    JavaResolveResult result = ref instanceof PsiExpression ? resolveOptimised(ref, myFile) : doVisitReferenceElement(ref);
    if (result != null) {
      PsiElement resolved = result.getElement();
      if (!hasErrorResults() && resolved instanceof PsiModifierListOwner) {
        PreviewFeatureUtil.checkPreviewFeature(ref, myPreviewFeatureVisitor);
      }
      if (!hasErrorResults()) {
        HighlightMethodUtil.checkAmbiguousConstructorCall(getProject(), ref, resolved, ref.getParent(), myJavaSdkVersion, myErrorSink);
      }
    }
  }

  private JavaResolveResult doVisitReferenceElement(@NotNull PsiJavaCodeReferenceElement ref) {
    JavaResolveResult result = resolveOptimised(ref, myFile);
    if (result == null) return null;

    PsiElement resolved = result.getElement();
    PsiElement parent = ref.getParent();

    add(HighlightUtil.checkReference(ref, result, myFile, myLanguageLevel));

    if ((parent instanceof PsiJavaCodeReferenceElement || ref.isQualified()) &&
        !hasErrorResults() &&
        resolved instanceof PsiTypeParameter) {
      boolean canSelectFromTypeParameter = myJavaSdkVersion.isAtLeast(JavaSdkVersion.JDK_1_7);
      if (canSelectFromTypeParameter) {
        PsiClass containingClass = PsiTreeUtil.getParentOfType(ref, PsiClass.class);
        if (containingClass != null) {
          if (PsiTreeUtil.isAncestor(containingClass.getExtendsList(), ref, false) ||
              PsiTreeUtil.isAncestor(containingClass.getImplementsList(), ref, false)) {
            canSelectFromTypeParameter = false;
          }
        }
      }
      if (!canSelectFromTypeParameter) {
        add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).descriptionAndTooltip(
          JavaErrorBundle.message("cannot.select.from.a.type.parameter")).range(ref));
      }
    }

    if (!hasErrorResults() && (!(parent instanceof PsiNewExpression newExpression) || !newExpression.isArrayCreation())) {
      add(GenericsHighlightUtil.checkParameterizedReferenceTypeArguments(resolved, ref, result.getSubstitutor(), myJavaSdkVersion));
    }

    if (resolved != null && parent instanceof PsiReferenceList referenceList && !hasErrorResults()) {
      add(HighlightUtil.checkElementInReferenceList(ref, referenceList, result));
    }

    if (parent instanceof PsiAnonymousClass psiAnonymousClass && ref.equals(psiAnonymousClass.getBaseClassReference())) {
      GenericsHighlightUtil.computeOverrideEquivalentMethodErrors(psiAnonymousClass, myOverrideEquivalentMethodsVisitedClasses, myOverrideEquivalentMethodsErrors);
      myErrorSink.accept(myOverrideEquivalentMethodsErrors.get(psiAnonymousClass));
      if (!hasErrorResults()) add(GenericsHighlightUtil.checkGenericCannotExtendException(psiAnonymousClass));
    }

    if (parent instanceof PsiNewExpression newExpression &&
        !(resolved instanceof PsiClass) &&
        resolved instanceof PsiNamedElement namedElement &&
        newExpression.getClassOrAnonymousClassReference() == ref) {
      String text = JavaErrorBundle.message("cannot.resolve.symbol", namedElement.getName());
      HighlightInfo.Builder info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(ref).descriptionAndTooltip(text);
      if (HighlightUtil.isCallToStaticMember(newExpression)) {
        var action = new RemoveNewKeywordFix(newExpression);
        info.registerFix(action, null, null, null, null);
      }
      add(info);
    }

    if (!hasErrorResults() && resolved instanceof PsiClass psiClass) {
      PsiClass aClass = psiClass.getContainingClass();
      if (aClass != null) {
        PsiElement qualifier = ref.getQualifier();
        PsiElement place;
        if (qualifier instanceof PsiJavaCodeReferenceElement element) {
          place = element.resolve();
        }
        else {
          if (parent instanceof PsiNewExpression newExpression) {
            PsiExpression newQualifier = newExpression.getQualifier();
            place = newQualifier == null ? ref : PsiUtil.resolveClassInType(newQualifier.getType());
          }
          else {
            place = ref;
          }
        }
        if (place != null &&
            PsiTreeUtil.isAncestor(aClass, place, false) &&
            aClass.hasTypeParameters() &&
            !PsiUtil.isInsideJavadocComment(place)) {
          add(HighlightClassUtil.checkCreateInnerClassFromStaticContext(ref, place, psiClass));
        }
      }
      else if (resolved instanceof PsiTypeParameter typeParameter) {
        PsiTypeParameterListOwner owner = typeParameter.getOwner();
        if (owner instanceof PsiClass outerClass) {
          if (!InheritanceUtil.hasEnclosingInstanceInScope(outerClass, ref, false, false)) {
            add(HighlightClassUtil.checkIllegalEnclosingUsage(ref, null, outerClass, ref));
          }
        }
        else if (owner instanceof PsiMethod) {
          PsiClass cls = ClassUtils.getContainingStaticClass(ref);
          if (cls != null && PsiTreeUtil.isAncestor(owner, cls, true)) {
            String description = JavaErrorBundle.message("cannot.be.referenced.from.static.context", ref.getReferenceName());
            add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(ref).descriptionAndTooltip(description));
          }
        }
      }
    }

    if (!hasErrorResults()) {
      add(HighlightUtil.checkPackageAndClassConflict(ref, myFile));
    }
    if (!hasErrorResults() && resolved instanceof PsiClass) {
      add(HighlightUtil.checkRestrictedIdentifierReference(ref, (PsiClass)resolved, myLanguageLevel));
    }
    if (!hasErrorResults()) {
      add(HighlightUtil.checkMemberReferencedBeforeConstructorCalled(ref, resolved, mySurroundingConstructor));
    }

    return result;
  }

  static @Nullable JavaResolveResult resolveOptimised(@NotNull PsiJavaCodeReferenceElement ref, @NotNull PsiFile containingFile) {
    try {
      if (ref instanceof PsiReferenceExpressionImpl) {
        PsiReferenceExpressionImpl.OurGenericsResolver resolver = PsiReferenceExpressionImpl.OurGenericsResolver.INSTANCE;
        JavaResolveResult[] results = JavaResolveUtil.resolveWithContainingFile(ref, resolver, true, true, containingFile);
        return results.length == 1 ? results[0] : JavaResolveResult.EMPTY;
      }
      return ref.advancedResolve(true);
    }
    catch (IndexNotReadyException e) {
      return null;
    }
  }

  private JavaResolveResult @Nullable [] resolveOptimised(@NotNull PsiReferenceExpression expression) {
    try {
      if (expression instanceof PsiReferenceExpressionImpl) {
        PsiReferenceExpressionImpl.OurGenericsResolver resolver = PsiReferenceExpressionImpl.OurGenericsResolver.INSTANCE;
        return JavaResolveUtil.resolveWithContainingFile(expression, resolver, true, true, myFile);
      }
      else {
        return expression.multiResolve(true);
      }
    }
    catch (IndexNotReadyException e) {
      return null;
    }
  }

  @Override
  public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
    JavaResolveResult resultForIncompleteCode = doVisitReferenceElement(expression);

    if (!hasErrorResults()) {
      visitExpression(expression);
      if (hasErrorResults()) return;
    }

    JavaResolveResult[] results = resolveOptimised(expression);
    if (results == null) return;
    JavaResolveResult result = results.length == 1 ? results[0] : JavaResolveResult.EMPTY;

    PsiElement resolved = result.getElement();
    if (resolved instanceof PsiVariable variable && resolved.getContainingFile() == expression.getContainingFile()) {
      boolean isFinal = variable.hasModifierProperty(PsiModifier.FINAL);
      if (isFinal && !variable.hasInitializer() && !(variable instanceof PsiPatternVariable)) {
        if (!hasErrorResults()) {
          add(HighlightControlFlowUtil.checkFinalVariableMightAlreadyHaveBeenAssignedTo(variable, expression, myFinalVarProblems));
        }
      }
      if (!hasErrorResults()) {
        try {
          add(HighlightControlFlowUtil.checkVariableInitializedBeforeUsage(expression, variable, myUninitializedVarProblems, myFile));
        }
        catch (IndexNotReadyException ignored) {
        }
      }
      if (!hasErrorResults() && resolved instanceof PsiLocalVariable localVariable) {
        add(HighlightUtil.checkVarTypeSelfReferencing(localVariable, expression));
      }
    }

    PsiElement parent = expression.getParent();
    if (parent instanceof PsiMethodCallExpression methodCallExpression &&
        methodCallExpression.getMethodExpression() == expression &&
        (!result.isAccessible() || !result.isStaticsScopeCorrect())) {
      PsiExpressionList list = methodCallExpression.getArgumentList();
      PsiResolveHelper resolveHelper = getResolveHelper();
      if (!HighlightMethodUtil.isDummyConstructorCall(methodCallExpression, resolveHelper, list, expression)) {
        try {
          add(HighlightMethodUtil.checkAmbiguousMethodCallIdentifier(
            expression, results, list, resolved, result, methodCallExpression, resolveHelper, myLanguageLevel, myFile));

          if (!PsiTreeUtil.findChildrenOfType(methodCallExpression.getArgumentList(), PsiLambdaExpression.class).isEmpty()) {
            PsiElement nameElement = expression.getReferenceNameElement();
            if (nameElement != null) {
              add(HighlightMethodUtil.checkAmbiguousMethodCallArguments(
                expression, results, list, resolved, result, methodCallExpression, resolveHelper, nameElement));
            }
          }
        }
        catch (IndexNotReadyException ignored) {
        }
      }
    }

    if (!hasErrorResults()) add(GenericsHighlightUtil.checkAccessStaticFieldFromEnumConstructor(expression, result));
    if (!hasErrorResults()) add(HighlightUtil.checkClassReferenceAfterQualifier(expression, resolved));
    PsiExpression qualifierExpression = expression.getQualifierExpression();
    add(HighlightUtil.checkUnqualifiedSuperInDefaultMethod(myLanguageLevel, expression, qualifierExpression));
    if (!hasErrorResults() && myJavaModule == null && qualifierExpression != null) {
      add(GenericsHighlightUtil.checkMemberSignatureTypesAccessibility(expression));
    }
    if (!hasErrorResults() && resolved instanceof PsiModifierListOwner) {
      PreviewFeatureUtil.checkPreviewFeature(expression, myPreviewFeatureVisitor);
    }
  }

  @Override
  public void visitMethodReferenceExpression(@NotNull PsiMethodReferenceExpression expression) {
    visitElement(expression);
    PsiElement parent = PsiUtil.skipParenthesizedExprUp(expression.getParent());
    if (toReportFunctionalExpressionProblemOnParent(parent)) return;
    PsiType functionalInterfaceType = expression.getFunctionalInterfaceType();
    if (functionalInterfaceType != null && !PsiTypesUtil.allTypeParametersResolved(expression, functionalInterfaceType)) return;

    JavaResolveResult result;
    JavaResolveResult[] results;
    try {
      results = expression.multiResolve(true);
      result = results.length == 1 ? results[0] : JavaResolveResult.EMPTY;
    }
    catch (IndexNotReadyException e) {
      return;
    }
    PsiElement method = result.getElement();
    if (method instanceof PsiJvmMember && !result.isAccessible()) {
      String accessProblem = HighlightUtil.accessProblemDescription(expression, method, result);
      HighlightInfo.Builder info =
        HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(accessProblem);
      HighlightFixUtil.registerAccessQuickFixAction(info, expression.getTextRange(), (PsiJvmMember)method, expression,
                                                    result.getCurrentFileResolveScope(), null);
      add(info);
    }

    if (!LambdaUtil.isValidLambdaContext(parent)) {
      String description = JavaErrorBundle.message("method.reference.expression.is.not.expected");
      add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(description));
    }

    if (!hasErrorResults()) {
      PsiElement referenceNameElement = expression.getReferenceNameElement();
      if (referenceNameElement instanceof PsiKeyword) {
        if (!PsiMethodReferenceUtil.isValidQualifier(expression)) {
          PsiElement qualifier = expression.getQualifier();
          if (qualifier != null) {
            boolean pending = qualifier instanceof PsiJavaCodeReferenceElement ref &&
                              IncompleteModelUtil.isIncompleteModel(expression) &&
                              IncompleteModelUtil.canBePendingReference(ref);
            if (!pending) {
              String description = JavaErrorBundle.message("cannot.find.class", qualifier.getText());
              add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(qualifier).descriptionAndTooltip(description));
            }
          }
        }
      }
    }

    if (functionalInterfaceType != null) {
      if (!hasErrorResults()) {
        add(HighlightClassUtil.checkExtendsSealedClass(expression, functionalInterfaceType));
      }
      if (!hasErrorResults()) {
        boolean isFunctional = LambdaUtil.isFunctionalType(functionalInterfaceType);
        if (!isFunctional && !(IncompleteModelUtil.isIncompleteModel(expression) &&
                               IncompleteModelUtil.isUnresolvedClassType(functionalInterfaceType))) {
          String description =
            JavaErrorBundle.message("not.a.functional.interface", functionalInterfaceType.getPresentableText());
          add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(description));
        }
      }
      if (!hasErrorResults()) {
        add(LambdaHighlightingUtil.checkFunctionalInterfaceTypeAccessible(myFile.getProject(), expression, functionalInterfaceType));
      }
      if (!hasErrorResults()) {
        String errorMessage = PsiMethodReferenceHighlightingUtil.checkMethodReferenceContext(expression);
        if (errorMessage != null) {
          HighlightInfo.Builder info =
            HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(errorMessage);
          if (method instanceof PsiMethod psiMethod &&
              !psiMethod.isConstructor() &&
              !psiMethod.hasModifierProperty(PsiModifier.ABSTRACT)) {
            boolean shouldHave = !psiMethod.hasModifierProperty(PsiModifier.STATIC);
            QuickFixAction.registerQuickFixActions(info, null, JvmElementActionFactories.createModifierActions(
              (JvmModifiersOwner)method, MemberRequestsKt.modifierRequest(JvmModifier.STATIC, shouldHave)));
          }
          add(info);
        }
      }
    }

    if (!hasErrorResults()) {
      PsiElement qualifier = expression.getQualifier();
      if (qualifier instanceof PsiTypeElement typeElement) {
        PsiType psiType = typeElement.getType();
        HighlightInfo.Builder genericArrayCreationInfo = GenericsHighlightUtil.checkGenericArrayCreation(qualifier, psiType);
        if (genericArrayCreationInfo != null) {
          add(genericArrayCreationInfo);
        }
        else {
          String wildcardMessage = PsiMethodReferenceUtil.checkTypeArguments(typeElement, psiType);
          if (wildcardMessage != null) {
            add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(qualifier).descriptionAndTooltip(wildcardMessage));
          }
        }
      }
    }

    if (method instanceof PsiMethod psiMethod && psiMethod.hasModifierProperty(PsiModifier.STATIC)) {
      if (!hasErrorResults() && psiMethod.hasTypeParameters()) {
        add(GenericsHighlightUtil.checkParameterizedReferenceTypeArguments(method, expression, result.getSubstitutor(), myJavaSdkVersion));
      }

      PsiClass containingClass = psiMethod.getContainingClass();
      if (!hasErrorResults() && containingClass != null && containingClass.isInterface()) {
        add(HighlightMethodUtil.checkStaticInterfaceCallQualifier(expression, result, expression, containingClass));
      }
    }

    if (!hasErrorResults()) {
      add(PsiMethodReferenceHighlightingUtil.checkRawConstructorReference(expression));
    }

    if (!hasErrorResults()) {
      boolean resolvedButNonApplicable = results.length == 1 && results[0] instanceof MethodCandidateInfo methodInfo &&
                                         !methodInfo.isApplicable() &&
                                         functionalInterfaceType != null;
      if (results.length != 1 || resolvedButNonApplicable) {
        String description = null;
        if (results.length == 1) {
          description = ((MethodCandidateInfo)results[0]).getInferenceErrorMessage();
        }
        if (expression.isConstructor()) {
          PsiClass containingClass = PsiMethodReferenceUtil.getQualifierResolveResult(expression).getContainingClass();

          if (containingClass != null && 
              containingClass.isPhysical() &&
              description == null) {
            description = JavaErrorBundle.message("cannot.resolve.constructor", containingClass.getName());
          }
        }
        else if (description == null) {
          if (results.length > 1) {
            if (IncompleteModelUtil.isIncompleteModel(expression) &&
                IncompleteModelUtil.isUnresolvedClassType(functionalInterfaceType)) {
              return;
            }
            String t1 = HighlightUtil.format(Objects.requireNonNull(results[0].getElement()));
            String t2 = HighlightUtil.format(Objects.requireNonNull(results[1].getElement()));
            description = JavaErrorBundle.message("ambiguous.reference", expression.getReferenceName(), t1, t2);
          }
          else {
            if (IncompleteModelUtil.isIncompleteModel(expression) && IncompleteModelUtil.canBePendingReference(expression)) {
              PsiElement referenceNameElement = expression.getReferenceNameElement();
              if (referenceNameElement != null) {
                add(HighlightUtil.getPendingReferenceHighlightInfo(referenceNameElement));
              }
              return;
            }
            
            if (!(resolvedButNonApplicable && HighlightMethodUtil.hasSurroundingInferenceError(expression))) {
              description = JavaErrorBundle.message("cannot.resolve.method", expression.getReferenceName());
            }
          }
        }

        if (description != null) {
          PsiElement referenceNameElement = ObjectUtils.notNull(expression.getReferenceNameElement(), expression);
          HighlightInfoType type = results.length == 0 ? HighlightInfoType.WRONG_REF : HighlightInfoType.ERROR;
          HighlightInfo.Builder highlightInfo =
            HighlightInfo.newHighlightInfo(type).descriptionAndTooltip(description).range(referenceNameElement);
          TextRange fixRange = HighlightMethodUtil.getFixRange(referenceNameElement);
          IntentionAction action = QuickFixFactory.getInstance().createCreateMethodFromUsageFix(expression);
          highlightInfo.registerFix(action, null, null, fixRange, null);
          add(highlightInfo);
        }
      }
    }

    if (!hasErrorResults()) {
      String badReturnTypeMessage = PsiMethodReferenceUtil.checkReturnType(expression, result, functionalInterfaceType);
      if (badReturnTypeMessage != null) {
        HighlightInfo.Builder info =
          HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(badReturnTypeMessage);
        IntentionAction action = AdjustFunctionContextFix.createFix(expression);
        if (action != null) {
          info.registerFix(action, null, null, null, null);
        }
        add(info);
      }
    }
    if (!hasErrorResults() && method instanceof PsiModifierListOwner) {
      PreviewFeatureUtil.checkPreviewFeature(expression, myPreviewFeatureVisitor);
    }
  }

  /**
   * @return true for {@code functional_expression;} or {@code var l = functional_expression;}
   */
  private static boolean toReportFunctionalExpressionProblemOnParent(@Nullable PsiElement parent) {
    if (parent instanceof PsiLocalVariable variable) {
      return variable.getTypeElement().isInferredType();
    }
    return parent instanceof PsiExpressionStatement && !(parent.getParent() instanceof PsiSwitchLabeledRuleStatement);
  }

  @Override
  public void visitReferenceList(@NotNull PsiReferenceList list) {
    super.visitReferenceList(list);
    if (list.getFirstChild() == null) return;
    PsiElement parent = list.getParent();
    if (!(parent instanceof PsiTypeParameter)) {
      if (!hasErrorResults()) HighlightClassUtil.checkPermitsList(list, myErrorSink);
      if (!hasErrorResults()) add(GenericsHighlightUtil.checkGenericCannotExtendException(list));
    }
  }

  @Override
  public void visitReferenceParameterList(@NotNull PsiReferenceParameterList list) {
    if (list.getTextLength() == 0) return;

    add(checkFeature(list, JavaFeature.GENERICS));
    if (!hasErrorResults()) add(GenericsHighlightUtil.checkParametersAllowed(list));
    if (!hasErrorResults()) add(GenericsHighlightUtil.checkParametersOnRaw(list, myLanguageLevel));
    if (!hasErrorResults()) {
      for (PsiTypeElement typeElement : list.getTypeParameterElements()) {
        if (typeElement.getType() instanceof PsiDiamondType) {
          add(checkFeature(list, JavaFeature.DIAMOND_TYPES));
        }
      }
    }
  }

  @Override
  public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
    super.visitStatement(statement);
    if (!hasErrorResults() && PsiUtil.isAvailable(JavaFeature.ENHANCED_SWITCH, myFile)) {
      add(HighlightUtil.checkReturnFromSwitchExpr(statement));
    }
    if (!hasErrorResults()) {
      try {
        PsiElement parent = PsiTreeUtil.getParentOfType(statement, PsiFile.class, PsiClassInitializer.class,
                                                        PsiLambdaExpression.class, PsiMethod.class);
        if (parent instanceof PsiMethod method ) {
          if (JavaPsiRecordUtil.isCompactConstructor(method)) {
            add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement)
                  .descriptionAndTooltip(JavaErrorBundle.message("record.compact.constructor.return")));
          }
          else if (method.isConstructor()) {
            PsiMethodCallExpression constructorCall = JavaPsiConstructorUtil.findThisOrSuperCallInConstructor(method);
            if (constructorCall != null && statement.getTextOffset() < constructorCall.getTextOffset()) {
              add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement)
                    .descriptionAndTooltip(JavaErrorBundle.message("return.statement.not.allowed.before.explicit.constructor.call",
                                                                   constructorCall.getMethodExpression().getText() + "()")));
            }
          }
        }
        if (!hasErrorResults() && parent != null) {
          HighlightInfo.Builder info = HighlightUtil.checkReturnStatementType(statement, parent);
          if (info != null && parent instanceof PsiMethod method) {
            PsiType expectedType = myExpectedReturnTypes.computeIfAbsent(method, HighlightMethodUtil::determineReturnType);
            if (expectedType != null && !PsiTypes.voidType().equals(expectedType)) {
              HighlightUtil.registerReturnTypeFixes(info, method, expectedType);
            }
          }
          add(info);
        }
      }
      catch (IndexNotReadyException ignore) {
      }
    }
  }

  @Override
  public void visitStatement(@NotNull PsiStatement statement) {
    super.visitStatement(statement);
    if (!hasErrorResults()) add(HighlightUtil.checkNotAStatement(statement));
  }

  @Override
  public void visitSuperExpression(@NotNull PsiSuperExpression expr) {
    add(HighlightUtil.checkThisOrSuperExpressionInIllegalContext(expr, expr.getQualifier(), myLanguageLevel));
    if (!hasErrorResults()) visitExpression(expr);
  }

  @Override
  public void visitSwitchStatement(@NotNull PsiSwitchStatement statement) {
    super.visitSwitchStatement(statement);
    checkSwitchBlock(statement);
  }

  @Override
  public void visitSwitchExpression(@NotNull PsiSwitchExpression expression) {
    super.visitSwitchExpression(expression);
    if (!hasErrorResults()) add(checkFeature(expression, JavaFeature.SWITCH_EXPRESSION));
    checkSwitchBlock(expression);
    if (!hasErrorResults()) HighlightUtil.checkSwitchExpressionReturnTypeCompatible(expression, myErrorSink);
    if (!hasErrorResults()) HighlightUtil.checkSwitchExpressionHasResult(expression, myErrorSink);
  }

  private void checkSwitchBlock(@NotNull PsiSwitchBlock switchBlock) {
    SwitchBlockHighlightingModel model = SwitchBlockHighlightingModel.createInstance(myLanguageLevel, switchBlock, myFile);
    if (model == null) return;
    if (!hasErrorResults()) model.checkSwitchBlockStatements(myErrorSink);
    if (!hasErrorResults()) model.checkSwitchSelectorType(myErrorSink);
    if (!hasErrorResults()) model.checkSwitchLabelValues(myErrorSink);
  }

  @Override
  public void visitThisExpression(@NotNull PsiThisExpression expr) {
    if (!(expr.getParent() instanceof PsiReceiverParameter)) {
      add(HighlightUtil.checkThisOrSuperExpressionInIllegalContext(expr, expr.getQualifier(), myLanguageLevel));
      if (!hasErrorResults()) {
        add(HighlightUtil.checkMemberReferencedBeforeConstructorCalled(expr, null, mySurroundingConstructor));
      }
      if (!hasErrorResults()) visitExpression(expr);
    }
  }

  @Override
  public void visitTryStatement(@NotNull PsiTryStatement statement) {
    super.visitTryStatement(statement);
    if (!hasErrorResults()) {
      UnhandledExceptions thrownTypes = HighlightUtil.collectUnhandledExceptions(statement);
      if (thrownTypes.hasUnresolvedCalls()) return;
      for (PsiParameter parameter : statement.getCatchBlockParameters()) {
        if (!hasErrorResults()) {
          HighlightUtil.checkWithImprovedCatchAnalysis(parameter, thrownTypes.exceptions(), myFile, myErrorSink);
        }
      }
    }
  }

  @Override
  public void visitResourceList(@NotNull PsiResourceList resourceList) {
    super.visitResourceList(resourceList);
    if (!hasErrorResults()) add(checkFeature(resourceList, JavaFeature.TRY_WITH_RESOURCES));
  }

  @Override
  public void visitResourceVariable(@NotNull PsiResourceVariable resource) {
    super.visitResourceVariable(resource);
    if (!hasErrorResults()) add(HighlightUtil.checkTryResourceIsAutoCloseable(resource));
    if (!hasErrorResults()) add(HighlightUtil.checkUnhandledCloserExceptions(resource));
  }

  @Override
  public void visitResourceExpression(@NotNull PsiResourceExpression resource) {
    super.visitResourceExpression(resource);
    if (!hasErrorResults()) add(checkFeature(resource, JavaFeature.REFS_AS_RESOURCE));
    if (!hasErrorResults()) add(HighlightUtil.checkResourceVariableIsFinal(resource));
    if (!hasErrorResults()) add(HighlightUtil.checkTryResourceIsAutoCloseable(resource));
    if (!hasErrorResults()) add(HighlightUtil.checkUnhandledCloserExceptions(resource));
  }

  @Override
  public void visitTypeElement(@NotNull PsiTypeElement type) {
    if (!hasErrorResults()) add(HighlightUtil.checkIllegalType(type, myFile));
    if (!hasErrorResults()) add(HighlightUtil.checkVarTypeApplicability(type));
    if (!hasErrorResults()) add(GenericsHighlightUtil.checkReferenceTypeUsedAsTypeArgument(type));
    if (!hasErrorResults()) add(GenericsHighlightUtil.checkWildcardUsage(type));
    if (!hasErrorResults()) add(HighlightUtil.checkArrayType(type));
    if (!hasErrorResults()) {
      PreviewFeatureUtil.checkPreviewFeature(type, myPreviewFeatureVisitor);
    }
  }

  @Override
  public void visitTypeCastExpression(@NotNull PsiTypeCastExpression typeCast) {
    super.visitTypeCastExpression(typeCast);
    try {
      if (!hasErrorResults()) add(HighlightUtil.checkIntersectionInTypeCast(typeCast, myLanguageLevel, myFile));
      if (!hasErrorResults()) add(HighlightUtil.checkInconvertibleTypeCast(typeCast));
    }
    catch (IndexNotReadyException ignored) {
    }
  }

  @Override
  public void visitVariable(@NotNull PsiVariable variable) {
    super.visitVariable(variable);
    if (variable instanceof PsiPatternVariable patternVariable) {
      PsiElement context = PsiTreeUtil.getParentOfType(variable,
                                                       PsiInstanceOfExpression.class,
                                                       PsiCaseLabelElementList.class,
                                                       PsiForeachPatternStatement.class);
      if (!(context instanceof PsiForeachPatternStatement)) {
        JavaFeature feature = context instanceof PsiInstanceOfExpression ?
                              JavaFeature.PATTERNS :
                              JavaFeature.PATTERNS_IN_SWITCH;
        PsiIdentifier varIdentifier = patternVariable.getNameIdentifier();
        add(checkFeature(varIdentifier, feature));
      }
    }
    try {
      if (!hasErrorResults()) add(HighlightUtil.checkVarTypeApplicability(variable));
      if (!hasErrorResults()) add(HighlightUtil.checkVariableInitializerType(variable));
    }
    catch (IndexNotReadyException ignored) {
    }
  }

  @Override
  public void visitConditionalExpression(@NotNull PsiConditionalExpression expression) {
    super.visitConditionalExpression(expression);
    if (!myLanguageLevel.isAtLeast(LanguageLevel.JDK_1_8) || !PsiPolyExpressionUtil.isPolyExpression(expression)) return;
    PsiExpression thenExpression = expression.getThenExpression();
    PsiExpression elseExpression = expression.getElseExpression();
    if (thenExpression == null || elseExpression == null) return;
    PsiType conditionalType = expression.getType();
    if (conditionalType == null) return;
    PsiExpression[] sides = {thenExpression, elseExpression};
    PsiType expectedType = null;
    boolean isExpectedTypeComputed = false;
    for (int i = 0; i < sides.length; i++) {
      PsiExpression side = sides[i];
      PsiExpression otherSide = i == 0 ? sides[1] : sides[0];
      PsiType sideType = side.getType();
      if (sideType != null && !TypeConversionUtil.isAssignable(conditionalType, sideType)) {
        HighlightInfo.Builder info = HighlightUtil.checkAssignability(conditionalType, sideType, side, side);
        if (info == null) continue;
        if (!TypeConversionUtil.isVoidType(sideType)) {
          if (TypeConversionUtil.isVoidType(otherSide.getType())) {
            HighlightUtil.registerChangeTypeFix(info, expression, sideType);
          }
          else {
            if (!isExpectedTypeComputed) {
              PsiElementFactory factory = PsiElementFactory.getInstance(expression.getProject());
              PsiExpression expressionCopy = factory.createExpressionFromText(expression.getText(), expression);
              expectedType = expressionCopy.getType();
              isExpectedTypeComputed = true;
            }
            if (expectedType != null) {
              HighlightUtil.registerChangeTypeFix(info, expression, expectedType);
            }
          }
        }
        add(info);
      }
    }
  }

  @Override
  public void visitModule(@NotNull PsiJavaModule module) {
    super.visitModule(module);
    if (!hasErrorResults()) add(checkFeature(module, JavaFeature.MODULES));
    if (!hasErrorResults()) add(ModuleHighlightUtil.checkFileName(module, myFile));
    if (!hasErrorResults()) add(ModuleHighlightUtil.checkFileDuplicates(module, myFile));
    if (!hasErrorResults()) ModuleHighlightUtil.checkDuplicateStatements(module, myErrorSink);
    if (!hasErrorResults()) add(ModuleHighlightUtil.checkClashingReads(module));
    if (!hasErrorResults()) ModuleHighlightUtil.checkUnusedServices(module, myFile, myErrorSink);
    if (!hasErrorResults()) add(ModuleHighlightUtil.checkFileLocation(module, myFile));
  }

  @Override
  public void visitModuleStatement(@NotNull PsiStatement statement) {
    super.visitModuleStatement(statement);
    if (!hasErrorResults()) {
      PreviewFeatureUtil.checkPreviewFeature(statement, myPreviewFeatureVisitor);
    }
  }

  @Override
  public void visitRequiresStatement(@NotNull PsiRequiresStatement statement) {
    super.visitRequiresStatement(statement);
    if (JavaFeature.MODULES.isSufficient(myLanguageLevel)) {
      if (!hasErrorResults()) add(ModuleHighlightUtil.checkModuleReference(statement));
      if (!hasErrorResults() && myLanguageLevel.isAtLeast(LanguageLevel.JDK_10)) {
        ModuleHighlightUtil.checkModifiers(statement, myErrorSink);
      }
    }
  }

  @Override
  public void visitPackageAccessibilityStatement(@NotNull PsiPackageAccessibilityStatement statement) {
    super.visitPackageAccessibilityStatement(statement);
    if (JavaFeature.MODULES.isSufficient(myLanguageLevel)) {
      if (!hasErrorResults()) add(ModuleHighlightUtil.checkHostModuleStrength(statement));
      if (!hasErrorResults()) add(ModuleHighlightUtil.checkPackageReference(statement, myFile));
      if (!hasErrorResults()) ModuleHighlightUtil.checkPackageAccessTargets(statement, myErrorSink);
    }
  }

  @Override
  public void visitUsesStatement(@NotNull PsiUsesStatement statement) {
    super.visitUsesStatement(statement);
    if (JavaFeature.MODULES.isSufficient(myLanguageLevel)) {
      if (!hasErrorResults()) add(ModuleHighlightUtil.checkServiceReference(statement.getClassReference()));
    }
  }

  @Override
  public void visitProvidesStatement(@NotNull PsiProvidesStatement statement) {
    super.visitProvidesStatement(statement);
    if (JavaFeature.MODULES.isSufficient(myLanguageLevel)) {
      if (!hasErrorResults()) ModuleHighlightUtil.checkServiceImplementations(statement, myFile, myErrorSink);
    }
  }

  @Override
  public void visitDefaultCaseLabelElement(@NotNull PsiDefaultCaseLabelElement element) {
    super.visitDefaultCaseLabelElement(element);
    // "case default:" will be highlighted as "The label for the default case must only use the 'default' keyword, without 'case'".
    // see SwitchBlockHighlightingModel#checkSwitchLabelValues
    // The "case default:" syntax was only allowed in outdated preview versions of Java (from Java 17 Preview to Java 19 Preview).
    // And even if the "case default" syntax was allowed not only in outdated preview versions of Java, using "case default:"
    // instead of "default:" looks weird. Therefore, for this case, we do not check the feature availability and do not
    // suggest increasing the language level.
  }

  @Override
  public void visitPatternVariable(@NotNull PsiPatternVariable variable) {
    super.visitPatternVariable(variable);
    if (variable.getPattern() instanceof PsiDeconstructionPattern) {
      String message = JavaErrorBundle.message("identifier.is.not.allowed.here");
      add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(variable).descriptionAndTooltip(message));
    }
  }

  @Override
  public void visitDeconstructionPattern(@NotNull PsiDeconstructionPattern deconstructionPattern) {
    super.visitDeconstructionPattern(deconstructionPattern);
    PsiTreeUtil.processElements(deconstructionPattern.getTypeElement(), PsiAnnotation.class, annotation -> {
      add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
            .range(annotation)
            .descriptionAndTooltip(JavaErrorBundle.message("deconstruction.pattern.type.contain.annotation")));
      return true;
    });
    PsiElement parent = deconstructionPattern.getParent();
    if (parent instanceof PsiForeachPatternStatement forEach) {
      add(checkFeature(deconstructionPattern, JavaFeature.RECORD_PATTERNS_IN_FOR_EACH));
      if (hasErrorResults()) return;
      PsiTypeElement typeElement = JavaPsiPatternUtil.getPatternTypeElement(deconstructionPattern);
      if (typeElement == null) return;
      PsiType patternType = typeElement.getType();
      PsiExpression iteratedValue = forEach.getIteratedValue();
      PsiType itemType = iteratedValue == null ? null : JavaGenericsUtil.getCollectionItemType(iteratedValue);
      if (itemType == null) return;
      PatternHighlightingModel.checkForEachPatternApplicable(deconstructionPattern, patternType, itemType, myErrorSink);
      if (hasErrorResults()) return;
      PsiClass selectorClass = PsiUtil.resolveClassInClassTypeOnly(TypeConversionUtil.erasure(itemType));
      if (selectorClass != null && (selectorClass.hasModifierProperty(SEALED) || selectorClass.isRecord())) {
        if (!PatternHighlightingModel.checkRecordExhaustiveness(Collections.singletonList(deconstructionPattern), patternType, forEach)
          .isExhaustive()) {
          add(PatternHighlightingModel.createPatternIsNotExhaustiveError(deconstructionPattern, patternType, itemType));
        }
      }
      else {
        add(PatternHighlightingModel.createPatternIsNotExhaustiveError(deconstructionPattern, patternType, itemType));
      }
    }
    else {
      add(checkFeature(deconstructionPattern, JavaFeature.PATTERN_GUARDS_AND_RECORD_PATTERNS));
    }
  }

  @Override
  public void visitDeconstructionList(@NotNull PsiDeconstructionList deconstructionList) {
    super.visitDeconstructionList(deconstructionList);
    // We are checking the case when the pattern looks similar to method call in switch and want to show user-friendly message that here
    // only constant expressions are expected.
    // it is required to do it in deconstruction list because unresolved reference won't let any parents show any highlighting,
    // so we need element which is not parent
    PsiElement parent = deconstructionList.getParent();
    PsiDeconstructionPattern pattern = tryCast(parent, PsiDeconstructionPattern.class);
    if (pattern == null) return;
    PsiElement grandParent = parent.getParent();
    if (!(grandParent instanceof PsiCaseLabelElementList)) return;
    PsiTypeElement typeElement = pattern.getTypeElement();
    PsiJavaCodeReferenceElement ref = PsiTreeUtil.getChildOfType(typeElement, PsiJavaCodeReferenceElement.class);
    if (ref == null) return;
    if (ref.multiResolve(true).length == 0) {
      PsiElementFactory elementFactory = PsiElementFactory.getInstance(myFile.getProject());
      if (pattern.getPatternVariable() == null && pattern.getDeconstructionList().getDeconstructionComponents().length == 0) {
        PsiClassType type = tryCast(pattern.getTypeElement().getType(), PsiClassType.class);
        if (type != null && ContainerUtil.exists(type.getParameters(), PsiWildcardType.class::isInstance)) return;
        PsiExpression expression = elementFactory.createExpressionFromText(pattern.getText(), grandParent);
        PsiMethodCallExpression call = tryCast(expression, PsiMethodCallExpression.class);
        if (call == null) return;
        if (call.getMethodExpression().resolve() != null) {
          add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(pattern.getTextRange())
                .descriptionAndTooltip(JavaErrorBundle.message("switch.constant.expression.required")));
        }
      }
    }
  }

  @Override
  public void visitTypeTestPattern(@NotNull PsiTypeTestPattern pattern) {
    super.visitTypeTestPattern(pattern);
    if (pattern.getParent() instanceof PsiCaseLabelElementList) {
      add(checkFeature(pattern, JavaFeature.PATTERNS_IN_SWITCH));
    }
  }

  @Override
  public void visitUnnamedPattern(@NotNull PsiUnnamedPattern pattern) {
    super.visitUnnamedPattern(pattern);
    add(checkFeature(pattern, JavaFeature.UNNAMED_PATTERNS_AND_VARIABLES));
  }

  private @Nullable HighlightInfo.Builder checkFeature(@NotNull PsiElement element, @NotNull JavaFeature feature) {
    return HighlightUtil.checkFeature(element, feature, myLanguageLevel, myFile);
  }

}

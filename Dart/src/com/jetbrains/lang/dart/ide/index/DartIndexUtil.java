package com.jetbrains.lang.dart.ide.index;

import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.indexing.FileContent;
import com.jetbrains.lang.dart.DartComponentType;
import com.jetbrains.lang.dart.psi.*;
import com.jetbrains.lang.dart.util.DartControlFlowUtil;
import com.jetbrains.lang.dart.util.DartResolveUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class DartIndexUtil {
  // inc when change parser
  public static int BASE_VERSION = 1;

  private static final Key<DartFileIndexData> ourDartCachesData = Key.create("dart.caches.index.data");

  public static DartFileIndexData indexFile(FileContent content) {
    DartFileIndexData indexData = content.getUserData(ourDartCachesData);
    if (indexData != null) return indexData;
    synchronized (content) {
      indexData = content.getUserData(ourDartCachesData);
      if (indexData != null) return indexData;
      indexData = indexFileRoots(content.getPsiFile());
    }

    return indexData;
  }

  private static DartFileIndexData indexFileRoots(PsiFile psiFile) {
    List<String> paths = new ArrayList<String>();
    DartFileIndexData result = new DartFileIndexData();
    for (PsiElement rootElement : findDartRoots(psiFile)) {
      PsiElement[] children = rootElement.getChildren();
      for (DartComponentName componentName : DartControlFlowUtil.getSimpleDeclarations(children, null, false)) {
        final String name = componentName.getName();
        if (name == null) {
          continue;
        }

        PsiElement parent = componentName.getParent();
        final DartComponentType type = DartComponentType.typeOf(parent);
        if (type != null) {
          result.addComponentInfo(name, new DartComponentInfo(psiFile.getName(), type));
        }
        if (parent instanceof DartClass) {
          result.addClassName(name);
          processInheritors(result, name, (DartClass)parent);
          for (DartComponent subComponent : DartResolveUtil.getNamedSubComponents((DartClass)parent)) {
            result.addSymbol(subComponent.getName());
          }
        }
      }

      for (PsiElement child : children) {
        if (child instanceof DartImportStatement) {
          processImportStatement(result, (DartImportStatement)child);
        }
        if (child instanceof DartSourceStatement) {
          final String pathValue = FileUtil.toSystemIndependentName(StringUtil.unquoteString(((DartSourceStatement)child).getPath()));
          result.addPath(pathValue);
        }
        if (child instanceof DartNativeStatement) {
          final String pathValue = FileUtil.toSystemIndependentName(StringUtil.unquoteString(((DartNativeStatement)child).getPath()));
          result.addPath(pathValue);
        }
      }
    }
    result.setLibraryName(DartResolveUtil.getLibraryName(psiFile));
    return result;
  }

  private static void processInheritors(DartFileIndexData result, @NotNull String dartClassName, DartClass dartClass) {
    final DartComponentInfo value = new DartComponentInfo(dartClassName, DartComponentType.typeOf(dartClass));
    final DartType superClass = dartClass.getSuperClass();
    if (superClass != null) {
      result.addInheritor(superClass.getReferenceExpression().getText(), value);
    }
    for (DartType dartType : DartResolveUtil.getImplementsAndMixinsList(dartClass)) {
      if (dartType == null) continue;
      result.addInheritor(dartType.getReferenceExpression().getText(), value);
    }
  }

  private static void processImportStatement(DartFileIndexData result, DartImportStatement importStatement) {
    final String pathValue = FileUtil.toSystemIndependentName(importStatement.getLibraryName());

    final Set<String> showComponentNames = new THashSet<String>();
    for (DartShowCombinator showCombinator : importStatement.getShowCombinatorList()) {
      for (DartExpression expression : showCombinator.getLibraryReferenceList().getLibraryComponentReferenceExpressionList()) {
        showComponentNames.add(expression.getText());
      }
    }

    final Set<String> hideComponentNames = new THashSet<String>();
    for (DartHideCombinator hideCombinator : importStatement.getHideCombinatorList()) {
      for (DartExpression expression : hideCombinator.getLibraryReferenceList().getLibraryComponentReferenceExpressionList()) {
        hideComponentNames.add(expression.getText());
      }
    }

    final DartComponentName prefixName = importStatement.getComponentName();
    final String prefix = prefixName != null ? prefixName.getName() : null;

    result.addImport(new DartPathInfo(pathValue, prefix, showComponentNames, hideComponentNames));
    result.addComponentInfo(prefix, new DartComponentInfo(importStatement.getContainingFile().getName(), DartComponentType.LABEL));
  }

  private static List<PsiElement> findDartRoots(PsiFile psiFile) {
    if (psiFile instanceof XmlFile) {
      return findDartRootsInXml((XmlFile)psiFile);
    }
    return Collections.<PsiElement>singletonList(psiFile);
  }

  private static List<PsiElement> findDartRootsInXml(XmlFile xmlFile) {
    final List<PsiElement> result = new ArrayList<PsiElement>();
    xmlFile.acceptChildren(new XmlRecursiveElementVisitor() {
      @Override
      public void visitElement(PsiElement element) {
        if (element instanceof DartEmbeddedContent) {
          result.add(element);
          return;
        }
        super.visitElement(element);
      }
    });
    return result;
  }
}

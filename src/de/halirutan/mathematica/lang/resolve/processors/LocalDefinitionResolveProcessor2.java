/*
 * Copyright (c) 2017 Patrick Scheibe
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package de.halirutan.mathematica.lang.resolve.processors;

import com.google.common.collect.Lists;
import com.intellij.psi.PsiElement;
import com.intellij.psi.ResolveState;
import com.intellij.psi.scope.BaseScopeProcessor;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import de.halirutan.mathematica.lang.psi.api.FunctionCall;
import de.halirutan.mathematica.lang.psi.api.Symbol;
import de.halirutan.mathematica.lang.psi.api.assignment.Set;
import de.halirutan.mathematica.lang.psi.api.assignment.SetDelayed;
import de.halirutan.mathematica.lang.psi.api.assignment.TagSetDelayed;
import de.halirutan.mathematica.lang.psi.api.rules.RuleDelayed;
import de.halirutan.mathematica.lang.psi.impl.SymbolPsiReference;
import de.halirutan.mathematica.lang.psi.util.LocalizationConstruct;
import de.halirutan.mathematica.lang.psi.util.LocalizationConstruct.MScope;
import de.halirutan.mathematica.lang.psi.util.MathematicaPatternVisitor;
import de.halirutan.mathematica.lang.psi.util.MathematicaPsiUtilities;
import de.halirutan.mathematica.lang.resolve.SymbolResolveResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Provides the functionality of resolving local references. This means, this class takes care to find out where a local
 * variable was defined and it can be used to find all references of a variable inside a scope. "Local" in this context
 * means that the variable must be localized with Module, Block, Table, Compile, etc..
 * <p/>
 * This class is for instance used by {@link SymbolPsiReference} but to give an overview of a complete flow let me
 * explain this in more detail:
 * <p/>
 * Let's assume you have "Preferences" -> "Editor" -> "Highlight usages of symbol under cursor" turned on and when you
 * browse through the code and stop on a variable, the variable itself and all its usages are highlighted. The moment
 * you move the cursor over the variable, IDEA calls {@link Symbol#getReference()} in order to find the place where this
 * variable is defined. The {@link SymbolPsiReference} first finds the correct PsiElement for which the reference should
 * be searched. Usually, this is always the {@link Symbol} over which the cursor is.
 * <p/>
 * Now {@link SymbolPsiReference#resolveInner()} searches clever for the place where the variable could be defined. This
 * process depends on the language; in Mathematica, I use currently the following approach: I walk the parsing tree
 * upwards and check every Module, Block, ... I find on my way. Checking means I look in the definition list whether my
 * variable is defined there. If not, I go further upwards. This is why you find a {@link
 * PsiTreeUtil#treeWalkUp(PsiScopeProcessor, PsiElement, PsiElement, ResolveState)} in this method. On every step
 * upwards the {@link #execute(PsiElement, ResolveState)} method is called and exactly here I extract all locally
 * defined variables I find and check whether any of it has the same name as my original variable whose definition I
 * want to find.
 * <p/>
 * If I find it in any of the localization constructs like Module, Block.. I stop and return the PsiElement which is the
 * place of definition.
 * <p/>
 * Finding all usages works btw the same way: First I find the definition of a variable and then I find all variables
 * which resolve to the exact same place of definition.
 *
 * @author patrick (5/22/13)
 */
public class LocalDefinitionResolveProcessor2 extends BaseScopeProcessor {

  private final Symbol myStartElement;
  private Symbol myReferringSymbol;
  private MScope myLocalization;
  private PsiElement myLocalizationSymbol = null;
  private SymbolResolveResult myResolveResult = null;

  public LocalDefinitionResolveProcessor2(Symbol startElement) {
    this.myStartElement = startElement;
    this.myReferringSymbol = null;
    this.myLocalization = MScope.NULL;

  }

  /**
   * There are several places where a local variable can be "defined". First I check all localization constructs which
   * are always function call like <code >Module[{blub},...]</code>. The complete list of localization constructs can be
   * found in {@link MScope}.
   * <p/>
   * Secondly I check the patterns in e.g. <code >f[var_]:=...</code> for <code >SetDelayed</code> and <code
   * >TagSetDelayed</code>.
   * <p/>
   * Finally, <code >RuleDelayed</code> constructs are checked.
   *
   * @param element
   *     Element to check for defining the {@link #myStartElement}.
   * @param state
   *     State of the resolving.
   * @return <code >false</code> if the search can be stopped, <code >true</code> otherwise
   */
  @Override
  public boolean execute(@NotNull PsiElement element, @NotNull ResolveState state) {
    if (element instanceof FunctionCall) {

      final PsiElement lastParent = state.get(SymbolResolveHint.LAST_PARENT);
      final FunctionCall functionCall = (FunctionCall) element;
      if (functionCall.isScopingConstruct()) {
        List<Symbol> vars = Lists.newArrayList();
        final MScope scopingConstruct = functionCall.getScopingConstruct();

        if (LocalizationConstruct.isFunctionLike(scopingConstruct)) {
          myResolveResult = MathematicaPsiUtilities.resolveLocalFunctionVariables(myStartElement, functionCall, state);
        } else if (LocalizationConstruct.isModuleLike(scopingConstruct)) {
          myResolveResult = MathematicaPsiUtilities.resolveLocalModuleLikeVariables(myStartElement, functionCall, state);
        } else if (LocalizationConstruct.isTableLike(scopingConstruct)) {
          myResolveResult = MathematicaPsiUtilities.resolveLocalTableLikeVariables(myStartElement, functionCall, state);
        } else if (LocalizationConstruct.isManipulateLike(scopingConstruct)) {
          myResolveResult = MathematicaPsiUtilities.resolveLocalTableLikeVariables(myStartElement, functionCall, state);
        } else if (LocalizationConstruct.isCompileLike(scopingConstruct)) {
          myResolveResult = MathematicaPsiUtilities.resolveLocalCompileLikeVariables(myStartElement, functionCall, state);
        }
//
//        if (LocalizationConstruct.isLimitLike(scopingConstruct)) {
//          vars = MathematicaPsiUtilities.getLocalLimitVariables(functionCall);
//        }
        if (myResolveResult != null) {
          myReferringSymbol = (Symbol) myResolveResult.getElement();
          myLocalization = myResolveResult.getLocalization();
          myLocalizationSymbol = element;
          return false;
        }
      }
    } else if (element instanceof SetDelayed || element instanceof TagSetDelayed || element instanceof Set) {

      MathematicaPatternVisitor patternVisitor = new MathematicaPatternVisitor();
      element.accept(patternVisitor);
      for (Symbol p : patternVisitor.getPatternSymbols()) {
        if (p.getFullSymbolName().equals(myStartElement.getFullSymbolName())) {
          if (element instanceof Set && myStartElement != p) {
            continue;
          }
          myReferringSymbol = p;
          myLocalization = MScope.SETDELAYEDPATTERN;
          myLocalizationSymbol = element;
          return false;
        }
      }
    } else if (element instanceof RuleDelayed) {
      MathematicaPatternVisitor patternVisitor = new MathematicaPatternVisitor();
      element.accept(patternVisitor);

      for (Symbol symbol : patternVisitor.getPatternSymbols()) {
        if (symbol.getFullSymbolName().equals(myStartElement.getFullSymbolName())) {
          myReferringSymbol = symbol;
          myLocalization = MScope.RULEDELAYED;
          myLocalizationSymbol = element;
          return false;
        }
      }


    }
    return true;
  }

  /**
   * Returns the list of all symbols collected during a {@link SymbolPsiReference#getVariants()} run. Before returning
   * the list, it removes duplicates, so that no entry appears more than once in the autocompletion window.
   *
   * @return Sorted and cleaned list of collected symbols.
   */
  @Nullable
  public Symbol getMyReferringSymbol() {
    return myReferringSymbol;
  }

  public MScope getMyLocalization() {
    return myLocalization;
  }

  public PsiElement getMyLocalizationSymbol() {
    return myLocalizationSymbol;
  }
}

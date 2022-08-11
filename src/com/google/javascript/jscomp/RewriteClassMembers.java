/*
 * Copyright 2021 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Replaces the ES2022 class fields and class static blocks with constructor declaration. */
public final class RewriteClassMembers implements NodeTraversal.Callback, CompilerPass {

  private static final String TEMP_MEM_FUNC_NAME = "$jscomp$mem$func$name$";
  private static final String TEMP_CLASS_NAME = "$jscomp$class$name$";
  private static final String TEMP_COMP_KEY = "$jscomp$comp$key$tmp$";

  private final AbstractCompiler compiler;
  private final AstFactory astFactory;
  private final UniqueIdSupplier uniqueIdSupplier;
  private final SynthesizeExplicitConstructors ctorCreator;
  private final Deque<ClassRecord> classStack;

  public RewriteClassMembers(AbstractCompiler compiler) {
    this.compiler = compiler;
    this.astFactory = compiler.createAstFactory();
    this.uniqueIdSupplier = compiler.getUniqueIdSupplier();
    this.ctorCreator = new SynthesizeExplicitConstructors(compiler);
    this.classStack = new ArrayDeque<>();
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, this);
    TranspilationPasses.maybeMarkFeaturesAsTranspiledAway(
        compiler, Feature.PUBLIC_CLASS_FIELDS, Feature.CLASS_STATIC_BLOCK);
  }

  @Override
  public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
    switch (n.getToken()) {
      case SCRIPT:
        FeatureSet scriptFeatures = NodeUtil.getFeatureSetOfScript(n);
        return scriptFeatures == null
            || scriptFeatures.contains(Feature.PUBLIC_CLASS_FIELDS)
            || scriptFeatures.contains(Feature.CLASS_STATIC_BLOCK);
      case CLASS:
        putClassInIIFE(t, n); // need to do this before rewriting
        Node classNameNode = NodeUtil.getNameNode(n);
        Node classInsertionPoint = getStatementDeclaringClass(n, classNameNode);
        if (classStack.isEmpty()) { // the lhs of this class is the global scope
          ThisSuperCollector collector = new ThisSuperCollector();
          NodeUtil.visitPreOrder(n, collector, NodeUtil.MATCH_SAME_THIS_OR_SUPER(null));
          if (!collector.superNodes.isEmpty()) {
            t.report(n, TranspilationUtil.CANNOT_CONVERT, "Illegal super usage");
          }
          collector.thisNodes.forEach(node -> replaceGlobalThis(t, node));
        }
        classStack.push(new ClassRecord(n, classNameNode.getQualifiedName(), classInsertionPoint));
        
        break;
      case COMPUTED_FIELD_DEF:
        checkState(!classStack.isEmpty());
        moveImpureComputedNames(
            n,
            classStack.peek().classNode,
            t); // want to move this first so we can get the proper name substitution in the
        // computed prop
        if (n.isStaticMember()) {
          findAndReplaceThisAndSuper(t, n.getSecondChild());
        } else if (n.getSecondChild() != null) {
          NodeUtil.visitPreOrder(n.getSecondChild(), classStack.peek());
        }
        classStack.peek().recordField(n);
        break;
      case COMPUTED_PROP:
        moveImpureComputedNames(n, classStack.peek().classNode, t);
        break;
      case MEMBER_FIELD_DEF:
        checkState(!classStack.isEmpty());
        if (n.isStaticMember()) {
          findAndReplaceThisAndSuper(t, n.getFirstChild());
        } else if (n.getFirstChild() != null) {
          NodeUtil.visitPreOrder(n.getFirstChild(), classStack.peek());
        }
        classStack.peek().recordField(n);
        break;
      case BLOCK:
        if (!NodeUtil.isClassStaticBlock(n)) {
          break;
        }
        if (NodeUtil.referencesEnclosingReceiver(n)) {
          t.report(n, TranspilationUtil.CANNOT_CONVERT_YET, "Member references this or super");
          classStack.peek().cannotConvert = true;
          break;
        }
        checkState(!classStack.isEmpty());
        findAndReplaceThisAndSuper(t, n);
        classStack.peek().recordStaticBlock(n);
        break;
      default:
        break;
    }
    return true;
  }

  private void findAndReplaceThisAndSuper(NodeTraversal t, Node root) {
    if (root == null) {
      return;
    }
    ThisSuperCollector collector = new ThisSuperCollector();
    NodeUtil.visitPreOrder(
        root, collector, NodeUtil.MATCH_SAME_THIS_OR_SUPER(classStack.peek().classNode));
    collector.thisNodes.forEach(this::replaceThis);
    if (collector.superNodes.isEmpty()) {
      return;
    }
    if (classStack.peek().classNode.getSecondChild().isEmpty()) {
      t.report(root, TranspilationUtil.CANNOT_CONVERT, "Super node with no superclass");
      return;
    }
    collector.superNodes.forEach(this::replaceSuper);
  }

  private void replaceThis(Node thisNode) {
    checkState(!classStack.isEmpty());
    Node classNode = classStack.peek().classNode;
    Node classNameNode = NodeUtil.getNameNode(classNode);

    Node nameToUse = classNameNode.isName() ? classNameNode.cloneNode() : classNameNode.cloneTree();

    thisNode.replaceWith(nameToUse);
  }

  private void replaceSuper(Node superNode) {
    checkState(!classStack.isEmpty());
    Node classNode = classStack.peek().classNode;
    Node superclass = classNode.getSecondChild();
    superNode.replaceWith(superclass.cloneNode());
  }

  private void replaceGlobalThis(NodeTraversal t, Node thisNode) {
    Node thisParent = thisNode.getParent();
    if (!thisParent.isGetProp()) {
      t.report(thisNode, TranspilationUtil.CANNOT_CONVERT, "Improper global this use");
      return;
    }
    thisNode.detach();
    String name = thisParent.getString();
    Node newName = astFactory.createName(name, AstFactory.type(thisParent));
    thisParent.replaceWith(newName);
    t.reportCodeChange();
  }

  /**
   * Moves all classes that do not have an extraction point for members or with conflicting class
   * names (let c = class C {}) into an IIFE
   *
   * <p>This method was chosen for its correctness and ease of implementation, but at the cost of
   * better optimizations
   *
   * <p>TODO(b/189993301, b/240443227): Explore other methods to improve optimizability
   */
  private void putClassInIIFE(NodeTraversal t, Node classNode) {
    Node classMembersExtractionNode =
        getStatementDeclaringClass(classNode, NodeUtil.getNameNode(classNode));
    if (classMembersExtractionNode != null // classes where we can extract members and where
        && (classNode.getFirstChild().isEmpty() // their names are consistent don't need to be moved
            || NodeUtil.getNameNode(classNode).matchesQualifiedName(classNode.getFirstChild()))) {
      return;
    }
    Node innerClassNameNode = classNode.getFirstChild();
    if (innerClassNameNode.isEmpty()) {
      CompilerInput input = t.getInput();
      String newClassName = TEMP_CLASS_NAME + uniqueIdSupplier.getUniqueId(input);
      Node newClassNameNode = astFactory.createName(newClassName, AstFactory.type(classNode));
      innerClassNameNode.replaceWith(newClassNameNode);
      innerClassNameNode = newClassNameNode;
    }
    Node returnClass = astFactory.createReturn(innerClassNameNode.cloneNode());
    Node iifeBlock = astFactory.createBlock(returnClass);
    Node iife = astFactory.createZeroArgArrowFunctionForExpression(iifeBlock);
    Node iifeCall = astFactory.createCall(iife, AstFactory.type(classNode));
    iifeCall.srcrefTreeIfMissing(classNode);
    classNode.replaceWith(iifeCall);
    iifeBlock.addChildToFront(classNode);
    t.reportCodeChange(iifeBlock);
    t.reportCodeChange();
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (n.isClass()) {
      visitClass(t);
    }
  }

  /** Transpile the actual class members themselves */
  private void visitClass(NodeTraversal t) {
    ClassRecord currClassRecord = classStack.pop();
    if (currClassRecord.cannotConvert) {
      return;
    }

    rewriteInstanceMembers(t, currClassRecord);
    rewriteStaticMembers(t, currClassRecord);
  }

  private void moveImpureComputedNames(Node member, Node classNode, NodeTraversal t) {
    checkArgument(member.isComputedFieldDef() || member.isComputedProp());
    Node computedName = member.getFirstChild();
    if (NodeUtil.canBeSideEffected(computedName)) {
      CompilerInput input = t.getInput();
      String genName = TEMP_COMP_KEY + uniqueIdSupplier.getUniqueId(input);
      Node newName = astFactory.createName(genName, AstFactory.type(computedName));
      computedName.replaceWith(newName);
      Node hoistedName = astFactory.createSingleConstNameDeclaration(genName, computedName);
      hoistedName.srcrefTreeIfMissing(computedName);
      hoistedName.insertBefore(NodeUtil.getEnclosingStatement(classNode));
    }
  }

  /** Rewrites and moves all instance fields */
  private void rewriteInstanceMembers(NodeTraversal t, ClassRecord record) {
    Deque<Node> instanceMembers = record.instanceMembers;

    if (instanceMembers.isEmpty()) {
      return;
    }
    ctorCreator.synthesizeClassConstructorIfMissing(t, record.classNode);
    Node ctor = NodeUtil.getEs6ClassConstructorMemberFunctionDef(record.classNode);
    Node ctorBlock = ctor.getFirstChild().getLastChild();
    Node insertionPoint = findInitialInstanceInsertionPoint(ctorBlock);

    Node newFunc = astFactory.createEmptyFunction(AstFactory.type(record.classNode));
    CompilerInput input = t.getInput();
    String newMembFuncName = TEMP_MEM_FUNC_NAME + uniqueIdSupplier.getUniqueId(input);
    Node newMembFunc = astFactory.createMemberFunctionDef(newMembFuncName, newFunc);
    Node newFunBlock = NodeUtil.getFunctionBody(newFunc);
    Node funProp =
        astFactory.createGetProp(
            astFactory.createThis(AstFactory.type(record.classNode)),
            newMembFuncName,
            AstFactory.type(record.classNode));
    Node funCall = astFactory.exprResult(astFactory.createCallWithUnknownType(funProp));
    funCall.srcrefTreeIfMissing(record.classNode);
    newMembFunc.srcrefTreeIfMissing(record.classNode);
    t.reportCodeChange(newFunBlock);

    if (insertionPoint == ctorBlock) {
      // insert the new function call at the beginning of the block, no super
      ctorBlock.addChildToFront(funCall);
    } else {
      funCall.insertAfter(insertionPoint);
    }

    Node classMembers = NodeUtil.getClassMembers(record.classNode);
    classMembers.addChildToFront(newMembFunc);

    while (!instanceMembers.isEmpty()) {
      Node instanceMember = instanceMembers.pop();
      checkState(instanceMember.isMemberFieldDef());

      Node thisNode = astFactory.createThisForEs6ClassMember(instanceMember);

      Node transpiledNode;
      switch (instanceMember.getToken()) {
        case MEMBER_FIELD_DEF:
          transpiledNode = convNonCompFieldToGetProp(thisNode, instanceMember.detach());

          break;
        case COMPUTED_FIELD_DEF:
          transpiledNode = convCompFieldToGetElem(thisNode, instanceMember.detach());
          break;
        default:
          throw new IllegalStateException(String.valueOf(instanceMember));
      }
      newFunBlock.addChildToFront(transpiledNode);
      t.reportCodeChange(); // we moved the field from the class body
      t.reportCodeChange(ctorBlock); // to the constructor, so we need both
    }
  }

  private Set<String> getNamesUsedInCtors() {
    return new HashSet<>();
  }

  /** Rewrites and moves all static blocks and fields */
  private void rewriteStaticMembers(NodeTraversal t, ClassRecord record) {
    Deque<Node> staticMembers = record.staticMembers;

    while (!staticMembers.isEmpty()) {
      Node staticMember = staticMembers.pop();
      // if the name is a property access, we want the whole chain of accesses, while for other
      // cases we only want the name node
      Node nameToUse =
          astFactory.createQNameWithUnknownType(record.classNameString).srcrefTree(staticMember);

      Node transpiledNode;

      switch (staticMember.getToken()) {
        case BLOCK:
          if (!NodeUtil.getVarsDeclaredInBranch(staticMember).isEmpty()) {
            transpiledNode = putBlockInIIFE(t, staticMember);
            break;
          }
          transpiledNode = staticMember.detach();
          break;
        case MEMBER_FIELD_DEF:
          transpiledNode = convNonCompFieldToGetProp(nameToUse, staticMember.detach());
          break;
        case COMPUTED_FIELD_DEF:
          transpiledNode = convCompFieldToGetElem(nameToUse, staticMember.detach());
          break;
        default:
          throw new IllegalStateException(String.valueOf(staticMember));
      }
      transpiledNode.insertAfter(record.classInsertionPoint);
      t.reportCodeChange();
    }
  }

  /**
   * Used to convert all static blocks with vars to an arrow function IIFE to prevent overwriting
   * shadowed vars from static class block,
   *
   * @return the IIFE <code><pre>
   *   var x = 2;
   *   class C {
   *     static {
   *       var x = 3;
   *     }
   *   }
   * </pre></code>
   */
  private Node putBlockInIIFE(NodeTraversal t, Node staticBlock) {
    Node iife = astFactory.createZeroArgArrowFunctionForExpression(staticBlock.detach());
    Node iifeCall = astFactory.createCallWithUnknownType(iife);
    iifeCall.srcrefTreeIfMissing(staticBlock);
    t.reportCodeChange(staticBlock);
    return astFactory.exprResult(iifeCall);
  }

  /**
   * Creates a node that represents receiver.key = value; where the key and value comes from the
   * non-computed field
   */
  private Node convNonCompFieldToGetProp(Node receiver, Node noncomputedField) {
    checkArgument(noncomputedField.isMemberFieldDef());
    checkArgument(noncomputedField.getParent() == null, noncomputedField);
    checkArgument(receiver.getParent() == null, receiver);
    Node getProp =
        astFactory.createGetProp(
            receiver, noncomputedField.getString(), AstFactory.type(noncomputedField));
    Node fieldValue = noncomputedField.getFirstChild();
    Node result =
        (fieldValue != null)
            ? astFactory.createAssignStatement(getProp, fieldValue.detach())
            : astFactory.exprResult(getProp);
    result.srcrefTreeIfMissing(noncomputedField);
    return result;
  }

  /**
   * Creates a node that represents receiver[key] = value; where the key and value comes from the
   * non-computed field
   */
  private Node convCompFieldToGetElem(Node receiver, Node computedField) {
    checkArgument(computedField.isComputedFieldDef());
    checkArgument(computedField.getParent() == null, computedField);
    checkArgument(receiver.getParent() == null, receiver);
    Node getElem = astFactory.createGetElem(receiver, computedField.getFirstChild().detach());
    Node fieldValue = computedField.getLastChild();
    Node result =
        (fieldValue != null)
            ? astFactory.createAssignStatement(getElem, fieldValue.detach())
            : astFactory.exprResult(getElem);
    result.srcrefTreeIfMissing(computedField);
    return result;
  }

  /**
   * Finds the location in the constructor to put the transpiled instance fields
   *
   * <p>Returns the constructor body if there is no super() call so the field can be put at the
   * beginning of the class
   *
   * <p>Returns the super() call otherwise so the field can be put after the super() call
   */
  private Node findInitialInstanceInsertionPoint(Node ctorBlock) {
    if (NodeUtil.referencesSuper(ctorBlock)) {
      // will use the fact that if there is super in the constructor, the first appearance of
      // super
      // must be the super call
      for (Node stmt = ctorBlock.getFirstChild(); stmt != null; stmt = stmt.getNext()) {
        if (NodeUtil.isExprCall(stmt) && stmt.getFirstFirstChild().isSuper()) {
          return stmt;
        }
      }
    }
    return ctorBlock; // in case the super loop doesn't work, insert at beginning of block
  }

  /**
   * Gets the location of the statement declaring the class
   *
   * @return null if the class cannot be extracted
   */
  @Nullable
  private Node getStatementDeclaringClass(Node classNode, Node classNameNode) {

    if (classNameNode == null) {
      // cannot extract static fields without a class name for assigning
      return null;
    }
    if (NodeUtil.isClassDeclaration(classNode)) {
      // `class C {}` -> can use `C.staticMember` to extract static fields
      checkState(NodeUtil.isStatement(classNode));
      return classNode;
    }
    final Node parent = classNode.getParent();
    if (parent.isName()) {
      // `let C = class {};`
      // We can use `C.staticMemberName = ...` to extract static fields
      checkState(parent == classNameNode);
      checkState(NodeUtil.isStatement(classNameNode.getParent()));
      return classNameNode.getParent();
    }
    if (parent.isAssign()
        && parent.getFirstChild() == classNameNode
        && parent.getParent().isExprResult()) {
      // `something.C = class {}`
      // we can use `something.C.staticMemberName = ...` to extract static fields
      checkState(NodeUtil.isStatement(classNameNode.getGrandparent()));
      return classNameNode.getGrandparent();
    }
    return null;
  }

  /**
   * Accumulates information about different classes while going down the AST in shouldTraverse()
   */
  private static final class ClassRecord implements NodeUtil.Visitor {
    boolean cannotConvert = false;
    final Deque<Node> instanceMembers =
        new ArrayDeque<>(); // instance computed + noncomputed fields
    final Deque<Node> staticMembers =
        new ArrayDeque<>(); // static blocks + computed + noncomputed fields
    final Set<String> memberReferredNames = new HashSet<>();
    @Nullable Scope classMemberScope = null;
    final Node classNode;
    final String classNameString;
    final Node classInsertionPoint;

    private ClassRecord(Node classNode, String classNameString, Node classInsertionPoint) {
      this.classNode = classNode;
      this.classNameString = classNameString;
      this.classInsertionPoint = classInsertionPoint;
    }

    private void recordField(Node field) {
      checkArgument(field.isComputedFieldDef() || field.isMemberFieldDef());
      if (field.isStaticMember()) {
        staticMembers.push(field);
      } else {
        instanceMembers.push(field);
      }
    }

    private void recordStaticBlock(Node block) {
      checkArgument(NodeUtil.isClassStaticBlock(block));
      staticMembers.push(block);
    }

    @Override
    /** Collects all of the names referenced inside a class member */
    public void visit(Node node) {
      if (!node.isName()) {
        return;
      }
      String name = node.getString();
      checkNotNull(classMemberScope);
      if (classMemberScope.hasSlot(name)) {
        memberReferredNames.add(name);
      }
    }
  }

  /** A visitor designed to collect all instances of `this` and `super` that fit a certain scope */
  private static class ThisSuperCollector implements NodeUtil.Visitor {
    final List<Node> thisNodes = new ArrayList<>();
    final List<Node> superNodes = new ArrayList<>();

    @Override
    public void visit(Node node) {
      if (node.isThis()) {
        thisNodes.add(node);
      }
      if (node.isSuper()) {
        superNodes.add(node);
      }
    }
  }
}

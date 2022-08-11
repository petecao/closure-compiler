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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test cases for transpilation pass that replaces public class fields and class static blocks:
 * <code><pre>
 * class C {
 *   x = 2;
 *   ['y'] = 3;
 *   static a;
 *   static ['b'] = 'hi';
 *   static {
 *     let c = 4;
 *     this.z = c;
 *   }
 * }
 * </pre></code>
 */
@RunWith(JUnit4.class)
public final class RewriteClassMembersTest extends CompilerTestCase {

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    enableTypeInfoValidation();
    enableTypeCheck();
    replaceTypesWithColors();
    enableMultistageCompilation();
  }

  @Override
  protected CompilerPass getProcessor(Compiler compiler) {
    return new RewriteClassMembers(compiler);
  }

  @Test
  public void testCannotConvertYet() {
    testError(
        lines(
            "/** @unrestricted */", //
            "class C {",
            "  ['x'] = 2;",
            "}"),
        TranspilationUtil.CANNOT_CONVERT_YET); // computed prop

    testError(
        lines(
            "/** @unrestricted */", //
            "class C {",
            "  static ['x'] = 2;",
            "}"),
        TranspilationUtil.CANNOT_CONVERT_YET); // computed prop

    testError(
        lines(
            "class C {", //
            "  static {",
            "    let x = 2",
            "    this.y = x",
            "  }",
            "}"),
        /*lines(
        "class C {}", //
        "{",
        "  let x = 2;",
        "  C.y = x", // TODO(b/235871861): Need to correct references to `this`
        "}")*/
        TranspilationUtil.CANNOT_CONVERT_YET); // uses `this`

    testError(
        lines(
            "class C extends B{", //
            "  static {",
            "    let x = super.y",
            "  }",
            "}"),
        TranspilationUtil.CANNOT_CONVERT_YET); // uses `super`

    testError(
        lines(
            "class C {", //
            "  static {",
            "    C.x = 2",
            "    const y = this.x",
            "  }",
            "}"),
        /*lines(
        "class C {}", //
        "{",
        "  C.x = 2;",
        "  const y = C.x",
        "}")*/
        TranspilationUtil.CANNOT_CONVERT_YET); // not class decl

    testError(
        lines(
            "class C {", //
            "  static x = 1;",
            "  static y = this.x;",
            "}"),
        TranspilationUtil.CANNOT_CONVERT_YET); // `this` in static field

    testError(
        lines(
            "class C {", //
            "  x = 1;",
            "  y = this.x;",
            "}"),
        TranspilationUtil.CANNOT_CONVERT_YET); // `this` in public field

    test(
        srcs(
            lines(
                "class C {", //
                "  static [1] = 1;",
                "  static [2] = this[1];",
                "}")),
        error(TranspilationUtil.CANNOT_CONVERT_YET),
        error(TranspilationUtil.CANNOT_CONVERT_YET)); // use of `this`

    test(
        srcs(
            lines(
                "let c = class C {", //
                "  static [1] = 2;",
                "  static [2] = C[1]",
                "}")),
        error(TranspilationUtil.CANNOT_CONVERT_YET)); // not class decl

    test(
        srcs(
            lines(
                "foo(class C {", //
                "  static [1] = 2;",
                "  static [2] = C[1]",
                "})")),
        error(TranspilationUtil.CANNOT_CONVERT_YET)); // not class decl

    testError(
        lines(
            "foo(class {", //
            "  static [1] = 1",
            "})"),
        TranspilationUtil.CANNOT_CONVERT_YET); // not class decl

    test(
        srcs(
            lines(
                "class C {", //
                "  [1] = 1;",
                "  [2] = this[1];",
                "}")),
        error(TranspilationUtil.CANNOT_CONVERT_YET),
        error(TranspilationUtil.CANNOT_CONVERT_YET)); // use of `this`

    test(
        srcs(
            lines(
                "let c = class C {", //
                "  static [1] = 2;",
                "  [2] = C[1]",
                "}")),
        error(TranspilationUtil.CANNOT_CONVERT_YET)); // not class decl

    test(
        srcs(
            lines(
                "foo(class C {", //
                "  static [1] = 2;",
                "  [2] = C[1]",
                "})")),
        error(TranspilationUtil.CANNOT_CONVERT_YET)); // not class decl

    test(
        srcs(
            lines(
                "let c = class {", //
                "  x = 1",
                "  y = this.x",
                "}",
                "class B {",
                "  [1] = 2;",
                "  [2] = this[1]",
                "}" // testing that the correct number of diagnostics are thrown
                )),
        error(TranspilationUtil.CANNOT_CONVERT_YET),
        error(TranspilationUtil.CANNOT_CONVERT_YET),
        error(TranspilationUtil.CANNOT_CONVERT_YET));
  }

  @Test
  public void testClassStaticBlocksNoFieldAssign() {
    test(
        lines(
            "class C {", //
            "  static {",
            "  }",
            "}"),
        lines(
            "class C {", //
            "}",
            "{}"));

    test(
        lines(
            "class C {", //
            "  static {",
            "    let x = 2",
            "    const y = x",
            "  }",
            "}"),
        lines(
            "class C {}", //
            "{",
            "  let x = 2;",
            "  const y = x",
            "}"));

    test(
        lines(
            "class C {", //
            "  static {",
            "    let x = 2",
            "    const y = x",
            "    let z;",
            "    if (x - y == 0) {z = 1} else {z = 2}",
            "    while (x - z > 10) {z++;}",
            "    for (;;) {break;}",
            "  }",
            "}"),
        lines(
            "class C {}", //
            "{",
            "  let x = 2;",
            "  const y = x",
            "  let z;",
            "  if (x - y == 0) {z = 1} else {z = 2}",
            "  while (x - z > 10) {z++;}",
            "  for (;;) {break;}",
            "}"));

    test(
        lines(
            "class C {", //
            "  static {",
            "    let x = 2",
            "  }",
            "  static {",
            "    const y = x",
            "  }",
            "}"),
        lines(
            "class C {}", //
            "{",
            "  let x = 2;",
            "}",
            "{",
            "  const y = x",
            "}"));

    test(
        lines(
            "class C {", //
            "  static {",
            "    let x = 2",
            "  }",
            "  static {",
            "    const y = x",
            "  }",
            "}",
            "class D {",
            "  static {",
            "    let z = 1",
            "  }",
            "}"),
        lines(
            "class C {}", //
            "{",
            "  let x = 2;",
            "}",
            "{",
            "  const y = x",
            "}",
            "class D {}",
            "{",
            "  let z = 1;",
            "}"));

    test(
        lines(
            "class C {", //
            "  static {",
            "    let x = function () {return 1;}",
            "    const y = () => {return 2;}",
            "    function a() {return 3;}",
            "    let z = (() => {return 4;})();",
            "  }",
            "}"),
        lines(
            "class C {}", //
            "{",
            "  let x = function () {return 1;}",
            "  const y = () => {return 2;}",
            "  function a() {return 3;}",
            "  let z = (() => {return 4;})();",
            "}"));

    test(
        lines(
            "class C {", //
            "  static {",
            "    C.x = 2",
            // "    const y = C.x", //TODO(b/235871861) blocked on typechecking, gets
            // JSC_INEXISTENT_PROPERTY
            "  }",
            "}"),
        lines(
            "class C {}", //
            "{",
            "  C.x = 2;",
            // "  const y = C.x",
            "}"));

    test(
        lines(
            "class Foo {",
            "  static {",
            "    let x = 5;",
            "    class Bar {",
            "      static {",
            "        let x = 'str';",
            "      }",
            "    }",
            "  }",
            "}"),
        lines(
            "class Foo {}", //
            "{",
            "  let x = 5;",
            "  class Bar {}",
            "  {let x = 'str';}",
            "}"));
  }

  @Test
  public void testStaticNoncomputed() {
    test(
        lines(
            "class C {", //
            "  static x = 2",
            "}"),
        lines("class C {}", "C.x = 2;"));

    test(
        lines(
            "class C {", //
            "  static x;",
            "}"),
        lines("class C {}", "C.x;"));

    test(
        lines(
            "class C {", //
            "  static x = 2",
            "  static y = 'hi'",
            "  static z;",
            "}"),
        lines("class C {}", "C.x = 2;", "C.y = 'hi'", "C.z;"));

    test(
        lines(
            "class C {", //
            "  static x = 2",
            "  static y = 3",
            "}",
            "class D {",
            "  static z = 1",
            "}"),
        lines(
            "class C {}", //
            "C.x = 2;",
            "C.y = 3",
            "class D {}",
            "D.z = 1;"));

    test(
        lines(
            "class C {", //
            "  static w = function () {return 1;};",
            "  static x = () => {return 2;};",
            "  static y = (function a() {return 3;})();",
            "  static z = (() => {return 4;})();",
            "}"),
        lines(
            "class C {}", //
            "C.w = function () {return 1;};",
            "C.x = () => {return 2;};",
            "C.y = (function a() {return 3;})();",
            "C.z = (() => {return 4;})();"));

    test(
        lines(
            "class C {", //
            "  static x = 2",
            "  static y = C.x",
            "}"),
        lines(
            "class C {}", //
            "C.x = 2;",
            "C.y = C.x"));

    test(
        lines(
            "class C {", //
            "  static x = 2",
            "  static {let y = C.x}",
            "}"),
        lines(
            "class C {}", //
            "C.x = 2;",
            "{let y = C.x}"));
  }

  @Test
  public void testInstanceNoncomputedWithNonemptyConstructor() {
    test(
        lines(
            "class C {", //
            "  x = 1;",
            "  constructor() {",
            "    this.y = 2;",
            "  }",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 1;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    this.y = 2",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x;",
            "  constructor() {",
            "    this.y = 2;",
            "  }",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    this.y = 2",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x = 1",
            "  y = 2",
            "  constructor() {",
            "    this.z = 3;",
            "  }",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 1;",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    this.z = 3;",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x = 1",
            "  y = 2",
            "  constructor() {",
            "    alert(3);",
            "    this.z = 4;",
            "  }",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 1;",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    alert(3);",
            "    this.z = 4;",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x = 1",
            "  constructor() {",
            "    alert(3);",
            "    this.z = 4;",
            "  }",
            "  y = 2",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 1;",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    alert(3);",
            "    this.z = 4;",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x = 1",
            "  constructor() {",
            "    alert(3);",
            "    this.z = 4;",
            "  }",
            "  y = 2",
            "}",
            "class D {",
            "  a = 5;",
            "  constructor() { this.b = 6;}",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 1;",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    alert(3);",
            "    this.z = 4;",
            "  }",
            "}",
            "class D {",
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.a = 5;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "    this.b = 6;",
            "  }",
            "}"));
  }

  @Test
  public void testInstanceNoncomputedWithNonemptyConstructorAndSuper() {
    test(
        lines(
            "class A { constructor() { alert(1); } }",
            "class C extends A {", //
            "  x = 1;",
            "  constructor() {",
            "    super()",
            "    this.y = 2;",
            "  }",
            "}"),
        lines(
            "class A { constructor() { alert(1); } }",
            "class C extends A {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 1;",
            "  }",
            "  constructor() {",
            "    super()",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    this.y = 2;",
            "  }",
            "}"));

    test(
        lines(
            "class A { constructor() { this.x = 1; } }",
            "class C extends A {", //
            "  y;",
            "  constructor() {",
            "    super()",
            "    alert(3);",
            "    this.z = 4;",
            "  }",
            "}"),
        lines(
            "class A { constructor() { this.x = 1; } }",
            "class C extends A {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y ;",
            "  }",
            "  constructor() {",
            "    super();",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    alert(3);",
            "    this.z = 4;",
            "  }",
            "}"));

    test(
        lines(
            "class A { constructor() { this.x = 1; } }",
            "class C extends A {", //
            "  y;",
            "  constructor() {",
            "    alert(3);",
            "    super()",
            "    this.z = 4;",
            "  }",
            "}"),
        lines(
            "class A { constructor() { this.x = 1; } }",
            "class C extends A {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y ;",
            "  }",
            "  constructor() {",
            "    alert(3);",
            "    super();",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    this.z = 4;",
            "  }",
            "}"));
  }

  @Test
  public void testNonComputedInstanceWithEmptyConstructor() {
    test(
        lines(
            "class C {", //
            "  x = 2;",
            "  constructor() {}",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x;",
            "  constructor() {}",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x = 2",
            "  y = 'hi'",
            "  z;",
            "  constructor() {}",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 2;",
            "    this.y = 'hi'",
            "    this.z",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x = 1",
            "  constructor() {",
            "  }",
            "  y = 2",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 1;",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x = 1",
            "  constructor() {",
            "  }",
            "  y = 2",
            "}",
            "class D {",
            "  a = 5;",
            "  constructor() {}",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 1;",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}",
            "class D {",
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.a = 5;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  w = function () {return 1;};",
            "  x = () => {return 2;};",
            "  y = (function a() {return 3;})();",
            "  z = (() => {return 4;})();",
            "  constructor() {}",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.w = function () {return 1;};",
            "    this.x = () => {return 2;};",
            "    this.y = (function a() {return 3;})();",
            "    this.z = (() => {return 4;})();",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  static x = 2",
            "  constructor() {}",
            "  y = C.x",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = C.x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}",
            "C.x = 2;"));
  }

  @Test
  public void testInstanceNoncomputedNoConstructor() {
    test(
        lines(
            "class C {", //
            "  x = 2;",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x;",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));

    test(
        lines(
            "class C {", //
            "  x = 2",
            "  y = 'hi'",
            "  z;",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 2;",
            "    this.y = 'hi';",
            "    this.z;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));
    test(
        lines(
            "class C {", //
            "  foo() {}",
            "  x = 1;",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 1;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "  foo() {}",
            "}"));

    test(
        lines(
            "class C {", //
            "  static x = 2",
            "  y = C.x",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = C.x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}", //
            "C.x = 2;"));

    test(
        lines(
            "class C {", //
            "  w = function () {return 1;};",
            "  x = () => {return 2;};",
            "  y = (function a() {return 3;})();",
            "  z = (() => {return 4;})();",
            "}"),
        lines(
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.w = function () {return 1;};",
            "    this.x = () => {return 2;};",
            "    this.y = (function a() {return 3;})();",
            "    this.z = (() => {return 4;})();",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));
  }

  @Test
  public void testInstanceNonComputedNoConstructorWithSuperclass() {
    test(
        lines(
            "class B {}", //
            "class C extends B {x = 1;}"),
        lines(
            "class B {}",
            "class C extends B {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = 1;",
            "  }",
            "  constructor() {",
            "    super(...arguments)",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));
    test(
        lines(
            "class B {constructor() {}; y = 2;}", //
            "class C extends B {x = 1;}"),
        lines(
            "class B {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}",
            "class C extends B {",
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.x = 1;",
            "  }",
            "  constructor() {",
            "    super(...arguments)",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "  }",
            "}"));
    test(
        lines(
            "class B {constructor(a, b) {}; y = 2;}", //
            "class C extends B {x = 1;}"),
        lines(
            "class B {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = 2;",
            "  }",
            "  constructor(a, b) {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}",
            "class C extends B {",
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.x = 1;",
            "  }",
            "  constructor() {",
            "    super(...arguments)",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "  }",
            "}"));
  }

  @Test
  public void testNonClassDeclarationsStaticBlocks() {
    test(
        lines(
            "let c = class {", //
            "  static {",
            "    let x = 1",
            "  }",
            "}"),
        lines("let c = class {}", "{", "  let x = 1", "}"));

    test(
        lines(
            "let c = class c {", //
            "  static {",
            "    let x = 1",
            "  }",
            "}"),
        lines("let c = class c {}", "{", "  let x = 1", "}"));

    test(
        lines(
            "let c = class C {", //
            "  static {",
            "    let x = 1",
            "  }",
            "}"),
        lines(
            "let c = (() => {", //
            "  class C {}",
            "  {",
            "    let x = 1",
            "  }",
            "  return C;",
            "})()"));

    test(
        lines(
            "class A {}",
            "A.c = class {", //
            "  static {",
            "    let x = 1",
            "  }",
            "}"),
        lines("class A {}", "A.c = class {}", "{", "  let x = 1", "}"));

    test(
        lines(
            "class A {}",
            "A[1] = class {", //
            "  static {",
            "    let x = 1",
            "  }",
            "}"),
        lines(
            "class A {}", //
            "A[1] = (() => { class $jscomp$class$name$m1146332801$0 {}",
            "{",
            "  let x = 1",
            "}",
            "return $jscomp$class$name$m1146332801$0",
            "})()"));
  }

  @Test
  public void testNonClassDeclarationsStaticNoncomputedFields() {
    test(
        lines(
            "let c = class {", //
            "  static x = 1",
            "}"),
        lines("let c = class {}", "c.x = 1"));

    test(
        lines(
            "let c = class c {", //
            "  static x = 1",
            "}"),
        lines("let c = class c {}", "c.x = 1"));

    test(
        lines(
            "let c = class C {", //
            "  static x = 1",
            "}"),
        lines(
            "let c = (() => {",
            "  class C {}", //
            "  C.x = 1;",
            "  return C;",
            "})()"));

    test(
        lines(
            "class A {}",
            "A.c = class {", //
            "  static x = 1",
            "}"),
        lines("class A {}", "A.c = class {}", "A.c.x = 1"));

    test(
        lines(
            "class A {}",
            "A[1] = class {", //
            "  static x = 1",
            "}"),
        lines(
            "class A {}", //
            "A[1] = (() => {",
            "  class $jscomp$class$name$m1146332801$0 {}",
            "  $jscomp$class$name$m1146332801$0.x = 1;",
            "  return $jscomp$class$name$m1146332801$0;",
            "})()"));
  }

  @Test
  public void testNonClassDeclarationsInstanceNoncomputedFields() {
    test(
        lines(
            "let c = class {", //
            "  y = 2;",
            "}"),
        lines(
            "let c = class {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));

    test(
        lines(
            "let c = class C {", //
            "  y = 2;",
            "}"),
        lines(
            "let c = (() => {",
            "  class C {", //
            "    $jscomp$mem$func$name$m1146332801$0() {",
            "      this.y = 2;",
            "    }",
            "    constructor() {",
            "      this.$jscomp$mem$func$name$m1146332801$0();",
            "    }",
            "  }",
            "  return C;",
            "})()"));

    test(
        lines(
            "let C = class C {", //
            "  y = 2;",
            "}"),
        lines(
            "let C =",
            "  class C {", //
            "    $jscomp$mem$func$name$m1146332801$0() {",
            "      this.y = 2;",
            "    }",
            "    constructor() {",
            "      this.$jscomp$mem$func$name$m1146332801$0();",
            "    }",
            "  }"));

    test(
        lines(
            "class A {}",
            "A.c = class {", //
            "  y = 2;",
            "}"),
        lines(
            "class A {}",
            "A.c = class {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));

    test(
        lines(
            "A[1] = class {", //
            "  y = 2;",
            "}"),
        lines(
            "A[1] = (() => {",
            "  class $jscomp$class$name$m1146332801$0 {", //
            "    $jscomp$mem$func$name$m1146332801$1() {",
            "      this.y = 2;",
            "    }",
            "    constructor() {",
            "      this.$jscomp$mem$func$name$m1146332801$1();",
            "    }",
            "  }",
            "  return $jscomp$class$name$m1146332801$0;",
            "}) ()"));

    test(
        lines(
            "foo(class {", //
            "  y = 2;",
            "})"),
        lines(
            "foo(",
            "(() => {",
            "  class $jscomp$class$name$m1146332801$0 {", //
            "    $jscomp$mem$func$name$m1146332801$1() {",
            "      this.y = 2;",
            "    }",
            "    constructor() {",
            "      this.$jscomp$mem$func$name$m1146332801$1();",
            "    }",
            "  }",
            "  return $jscomp$class$name$m1146332801$0;",
            "})",
            "())"));

    test(
        lines(
            "class A {}",
            "A.c = class C {", //
            "  y = 2;",
            "}"),
        lines(
            "class A {}",
            "A.c = (() => {",
            "  class C {", //
            "    $jscomp$mem$func$name$m1146332801$0() {",
            "      this.y = 2;",
            "    }",
            "    constructor() {",
            "      this.$jscomp$mem$func$name$m1146332801$0();",
            "    }",
            "  }",
            "  return C;",
            "})()"));

    test(
        lines(
            "A[1] = class C {", //
            "  y = 2;",
            "}"),
        lines(
            "A[1] = (() => { class C {", //
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}",
            "return C",
            "})()"));

    test(
        lines(
            "foo(class C {", //
            "  y = 2;",
            "})"),
        lines(
            "foo((() => {class C {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = 2;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}",
            "return C",
            "})())"));
  }

  @Test
  public void testPrefilledConstructorStaticField() {
    test(
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor(x) {}",
            "  static y = x",
            "}"),
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor(x) {}",
            "}",
            "C.y = x"));

    test(
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor() {var x = 3;}",
            "  static y = x",
            "}"),
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor() {var x = 3;}",
            "}",
            "C.y = x"));
  }

  @Test
  public void testPrefilledConstructorInstanceField() {
    test(
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor(x) {}",
            "  y = x",
            "}"),
        lines(
            "let x = 2;",
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = x;",
            "  }",
            "  constructor(x) {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "  }",
            "}"));

    test(
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor() {var x = 3;}",
            "  y = x",
            "}"),
        lines(
            "let x = 2;",
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    var x = 3;",
            "  }",
            "}"));

    test(
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor() {let x = 3;}",
            "  y = x",
            "}"),
        lines(
            "let x = 2;",
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    let x = 3;",
            "  }",
            "}"));

    test(
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor() {{let x = 3;}}",
            "  y = x",
            "}"),
        lines(
            "let x = 2;",
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    {let x = 3;}",
            "  }",
            "}"));

    test(
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor() {{var x = 3;}}",
            "  y = x",
            "}"),
        lines(
            "let x = 2;",
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    {var x = 3;}",
            "  }",
            "}"));

    test(
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor() {function fun() {var x = 3;}}",
            "  y = x;",
            "}"),
        lines(
            "let x = 2;", //
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    function fun() {var x = 3;}",
            "  }",
            "}"));

    test(
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor() {() => {var x = 3;}}",
            "  y = x",
            "}"),
        lines(
            "let x = 2;", //
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    () => {var x = 3;}",
            "  }",
            "}"));

    test(
        lines(
            "let x = 2;", //
            "class C {",
            "  constructor() {function f(x) {}}",
            "  y = x",
            "}"),
        lines(
            "let x = 2;", //
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = x;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    function f(x) {}",
            "  }",
            "}"));

    test(
        lines(
            "function f() { return 4; };", //
            "class C {",
            "  y = f();",
            "  constructor() {function f() { return 'str'; }}",
            "}"),
        lines(
            "function f() { return 4; };", //
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.y = f();",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    function f() { return 'str'; }",
            "  }",
            "}"));
  }

  @Test
  public void testPrefilledConstructorNestedClasses() {
    test(
        lines(
            "class C {", //
            "  static y = 3;",
            "  constructor() {",
            "    class D {",
            "      constructor(x) {}",
            "      static x = 2;",
            "    }",
            "  }",
            "}"),
        lines(
            "class C {", //
            "  constructor() {",
            "    class D {",
            "      constructor(x) {}",
            "    }",
            "    D.x = 2;",
            "  }",
            "}",
            "C.y = 3;"));

    test(
        lines(
            "class C {", //
            "  static y = 3;",
            "  constructor() {}",
            "  static {",
            "    class D {",
            "      constructor(x) {}",
            "      static x = 2;",
            "    }",
            "  }",
            "}"),
        lines(
            "class C {", //
            "  constructor() {}",
            "}",
            "C.y = 3;",
            "{",
            "  class D {",
            "    constructor(x) {}",
            "  }",
            "  D.x = 2;",
            "}"));

    test(
        lines(
            "class C {}",
            "class D {",
            "  x = new C()",
            "  constructor() {",
            "    class C {}",
            "  }",
            "}"),
        lines(
            "class C {}",
            "class D {",
            "  $jscomp$mem$func$name$m1146332801$0() {",
            "    this.x = new C();",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$0();",
            "    class C {}",
            "  }",
            "}"));

    test(
        lines(
            "let x = 1",
            "class C {", //
            "  y = 3;",
            "  constructor() {",
            "    let x = 5;",
            "    class D {",
            "      constructor(x) {}",
            "      z = 2;",
            "    }",
            "  }",
            "}"),
        lines(
            "let x = 1",
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.y = 3;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "    let x = 5;",
            "    class D {",
            "      $jscomp$mem$func$name$m1146332801$0() {",
            "        this.z = 2;",
            "      }",
            "      constructor(x) {",
            "        this.$jscomp$mem$func$name$m1146332801$0();",
            "      }",
            "    }",
            "  }",
            "}"));

    test(
        lines(
            "let x = 1",
            "class C {", //
            "  y = 3;",
            "  constructor() {",
            "    let x = 5;",
            "    class D {",
            "      constructor() {}",
            "      z = 2;",
            "    }",
            "  }",
            "}"),
        lines(
            "let x = 1",
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.y = 3;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "    let x = 5;",
            "    class D {",
            "      $jscomp$mem$func$name$m1146332801$0() {",
            "        this.z = 2;",
            "      }",
            "      constructor() {",
            "        this.$jscomp$mem$func$name$m1146332801$0();",
            "      }",
            "    }",
            "  }",
            "}"));

    test(
        lines(
            "let x = 1",
            "class C {", //
            "  y = 3;",
            "  constructor() {",
            "    class D {",
            "      constructor(x) {}",
            "      z = 2;",
            "    }",
            "  }",
            "}"),
        lines(
            "let x = 1",
            "class C {", //
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.y = 3;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "    class D {",
            "      $jscomp$mem$func$name$m1146332801$0() {",
            "        this.z = 2;",
            "      }",
            "      constructor(x) {",
            "        this.$jscomp$mem$func$name$m1146332801$0();",
            "      }",
            "    }",
            "  }",
            "}"));
  }

  @Test
  public void testNonClassDeclarationsFunctionArgs() {
    test(
        lines(
            "class A {}", //
            "A[foo()] = class {static x;}"),
        lines(
            "class A {}",
            "A[foo()] = (() => {",
            "  class $jscomp$class$name$m1146332801$0 {}",
            "  $jscomp$class$name$m1146332801$0.x;",
            "  return $jscomp$class$name$m1146332801$0",
            "})();"));
    test(
        lines(
            "class A {}", //
            "A[foo()] = class C {static x;}"),
        lines(
            "class A {}", "A[foo()] = (() => {", "  class C {}", "  C.x;", "  return C", "})();"));

    test(
        "foo(c = class {static x;})",
        lines(
            "foo( c = (() => {",
            "  class $jscomp$class$name$m1146332801$0 {}",
            "  $jscomp$class$name$m1146332801$0.x;",
            "  return $jscomp$class$name$m1146332801$0",
            "})())"));

    test(
        "foo(c = class C {static x;})",
        lines("foo( c = (() => {", "  class C {}", "  C.x;", "  return C", "})())"));

    test(
        "function foo(c = class {static x;}) {}",
        lines(
            "function foo( c = (() => {",
            "  class $jscomp$class$name$m1146332801$0 {}",
            "  $jscomp$class$name$m1146332801$0.x;",
            "  return $jscomp$class$name$m1146332801$0",
            "})()) {}"));

    test(
        "function foo(c = class C {static x;}) {}",
        lines("function foo( c = (() => {", "  class C {}", "  C.x;", "  return C", "})()) {}"));
  }

  @Test
  public void testIIFEClassesWithStaticMembers() {
    test(
        lines(
            "foo(class C {", //
            "  static {",
            "    C.y = 2;",
            "    let x = C.y",
            "  }",
            "})"),
        lines(
            "foo((() => {",
            "  class C {}", //
            "  {",
            "    C.y = 2;",
            "    let x = C.y",
            "  }",
            "  return C;",
            "})())"));

    test(
        lines(
            "class A {}",
            "A.b = class {}",
            "foo(A.b.c = class C {", //
            "  static {",
            "    C.y = 2;",
            "    let x = C.y",
            "  }",
            "})"),
        lines(
            "class A {}",
            "A.b = class {}",
            "foo(A.b.c = (() => { class C {}", //
            "  {",
            "    C.y = 2;",
            "    let x = C.y",
            "  }",
            "  return C;",
            "})())"));

    test(
        lines(
            "foo(class {", //
            "  static {",
            "    let x = 1",
            "  }",
            "})"),
        lines(
            "foo((() => {class $jscomp$class$name$m1146332801$0 {}", //
            "  {",
            "    let x = 1",
            "  }",
            "  return $jscomp$class$name$m1146332801$0",
            "})())"));

    test(
        lines(
            "foo(class C {", //
            "  static y = 2;",
            "  static x = C.y",
            "})"),
        lines(
            "foo((() => {",
            "  class C {}", //
            "  C.y = 2;",
            "  C.x = C.y",
            "  return C;",
            "})())"));

    test(
        lines(
            "foo(class {", //
            "  static x = 1",
            "})"),
        lines(
            "foo((() => {",
            "  class $jscomp$class$name$m1146332801$0 {}", //
            "  $jscomp$class$name$m1146332801$0.x = 1",
            "  return $jscomp$class$name$m1146332801$0",
            "})())"));

    test(
        lines(
            "foo(class C {", //
            "  static y = 2;",
            "  x = C.y",
            "})"),
        lines(
            "foo((() => {",
            "  class C {", //
            "    $jscomp$mem$func$name$m1146332801$0() {",
            "      this.x = C.y;",
            "    }",
            "    constructor() {",
            "      this.$jscomp$mem$func$name$m1146332801$0();",
            "    }",
            "  }",
            "  C.y = 2;",
            "  return C;",
            "})())"));

    test(
        lines(
            "let c = class C {", //
            "  static {",
            "    C.y = 2;",
            "    let x = C.y",
            "  }",
            "}"),
        lines(
            "let c = (() => {",
            "  class C {}", //
            "  {",
            "    C.y = 2;",
            "    let x = C.y",
            "  }",
            "  return C",
            "})()"));

    test(
        lines(
            "let c = class C {", //
            "  static y = 2;",
            "  static x = C.y",
            "}"),
        lines(
            "let c = (() => {",
            "  class C {}", //
            "  C.y = 2;",
            "  C.x = C.y",
            "  return C;",
            "})()"));
  }

  @Test
  public void testClassesInsideClasses() {
    test(
        lines(
            "class C {",
            "  static {",
            "    let c = class {",
            "      static x = 1",
            "      static {let y = 2;}",
            "      z = 3;",
            "    }",
            "  }",
            "  a = 4;",
            "  static b = 5;",
            "}"),
        lines(
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.a = 4;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "  }",
            "}",
            "{",
            "  let c = class {",
            "    $jscomp$mem$func$name$m1146332801$0() {",
            "      this.z = 3;",
            "    }",
            "    constructor() {",
            "      this.$jscomp$mem$func$name$m1146332801$0();",
            "    }",
            "  };",
            "  c.x = 1;",
            "  {",
            "   let y = 2;",
            "  }",
            "}",
            "C.b = 5;"));

    test(
        lines(
            "class C {",
            "  static {",
            "    let c = 4;",
            "  }",
            "  a = class {",
            "    static x = 1",
            "    static {let y = 2;}",
            "    z = 3;",
            "  }",
            "  static b = 5;",
            "}"),
        lines(
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$2() {",
            "    this.a = (() => { class $jscomp$class$name$m1146332801$0 {",
            "      $jscomp$mem$func$name$m1146332801$1() {",
            "        this.z = 3;",
            "      }",
            "      constructor() {",
            "        this.$jscomp$mem$func$name$m1146332801$1();",
            "      }",
            "    }",
            "    $jscomp$class$name$m1146332801$0.x = 1;",
            "    {let y = 2;}",
            "    return $jscomp$class$name$m1146332801$0;",
            "    })()",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$2();",
            "  }",
            "}",
            "{",
            "  let c = 4;",
            "}",
            "C.b = 5;"));

    test(
        lines(
            "class C {",
            "  static {",
            "    let c = 5;",
            "  }",
            "  a = 4;",
            "  static b = class {",
            "    static x = 1",
            "    static {let y = 2;}",
            "    z = 3;",
            "  }",
            "}"),
        lines(
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$2() {",
            "    this.a = 4;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$2();",
            "  }",
            "}",
            "{",
            "  let c = 5;",
            "}",
            "C.b = (() => { class $jscomp$class$name$m1146332801$0 {",
            "    $jscomp$mem$func$name$m1146332801$1() {",
            "      this.z = 3;",
            "    }",
            "    constructor() {",
            "      this.$jscomp$mem$func$name$m1146332801$1();",
            "    }",
            "  }",
            "  $jscomp$class$name$m1146332801$0.x = 1;",
            "  {let y = 2;}",
            "  return $jscomp$class$name$m1146332801$0;",
            "})()"));

    test(
        lines(
            "class C {",
            "  static {",
            "    let c = class C {",
            "      static x = 1",
            "      static {let y = 2;}",
            "      z = 3;",
            "    }",
            "  }",
            "  a = 4;",
            "  static b = 5;",
            "}"),
        lines(
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.a = 4;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "  }",
            "}",
            "{",
            "  let c = (() => { class C {",
            "    $jscomp$mem$func$name$m1146332801$0() {",
            "      this.z = 3;",
            "    }",
            "    constructor() {",
            "      this.$jscomp$mem$func$name$m1146332801$0();",
            "    }",
            "  }",
            "    C.x = 1;",
            "    {",
            "    let y = 2;",
            "    }",
            "    return C;",
            "  })();",
            "}",
            "C.b = 5;"));

    test(
        lines(
            "class C {",
            "  static {",
            "    let c = 4;",
            "  }",
            "  a = class A {",
            "    static x = 1",
            "    static {let y = 2;}",
            "    z = 3;",
            "  }",
            "  static b = 5;",
            "}"),
        lines(
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.a = (() => { class A {",
            "      $jscomp$mem$func$name$m1146332801$0() {",
            "        this.z = 3;",
            "      }",
            "      constructor() {",
            "        this.$jscomp$mem$func$name$m1146332801$0();",
            "      }",
            "    }",
            "    A.x = 1;",
            "    {let y = 2;}",
            "    return A;",
            "    })()",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "  }",
            "}",
            "{",
            "  let c = 4;",
            "}",
            "C.b = 5;"));

    test(
        lines(
            "class C {",
            "  static {",
            "    let c = 5;",
            "  }",
            "  a = 4;",
            "  static b = class B {",
            "    static x = 1",
            "    static {let y = 2;}",
            "    z = 3;",
            "  }",
            "}"),
        lines(
            "class C {",
            "  $jscomp$mem$func$name$m1146332801$1() {",
            "    this.a = 4;",
            "  }",
            "  constructor() {",
            "    this.$jscomp$mem$func$name$m1146332801$1();",
            "  }",
            "}",
            "{",
            "  let c = 5;",
            "}",
            "C.b = (() => { class B {",
            "    $jscomp$mem$func$name$m1146332801$0() {",
            "      this.z = 3;",
            "    }",
            "    constructor() {",
            "      this.$jscomp$mem$func$name$m1146332801$0();",
            "    }",
            "  }",
            "  B.x = 1;",
            "  {let y = 2;}",
            "  return B;",
            "})()"));
  }

  @Test
  public void testStaticBlocksWithVar() {
    test(
        lines(
            "var z = 1", //
            "class C {",
            "  static {",
            "    let x = 2",
            "    var z = 3;",
            "  }",
            "}"),
        lines(
            "var z = 1", //
            "class C {}",
            "(() => {",
            "  let x = 2",
            "  var z = 3;",
            "})()")); // `var` in static block

    test(
        lines(
            "class Foo {",
            "  static {",
            "    let x = 5;",
            "    class Bar {",
            "      static {",
            "        var x = 'str';",
            "      }",
            "    }",
            "  }",
            "}"),
        lines(
            "class Foo {}", //
            "{",
            "  let x = 5;",
            "  class Bar {}",
            "  (() => {var x = 'str';})()",
            "}"));
  }
}

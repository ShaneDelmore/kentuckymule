package dotty.tools
package dotc

import kentuckymule.core.Types.Type
import util.SourceFile
import ast.{untpd}

class CompilationUnit(val source: SourceFile) {

  override def toString = source.toString

  var untpdTree: untpd.Tree = untpd.EmptyTree

//  var tpdTree: tpd.Tree = tpd.EmptyTree

  def isJava = source.file.name.endsWith(".java")

//  /**
//   * Picklers used to create TASTY sections, indexed by toplevel class to which they belong.
//   * Sections: Header, ASTs and Positions are populated by `pickler` phase.
//   * Subsequent phases can add new sections.
//   */
//  var picklers: Map[ClassSymbol, TastyPickler] = Map()
//
//  var unpicklers: Map[ClassSymbol, TastyUnpickler] = Map()
}

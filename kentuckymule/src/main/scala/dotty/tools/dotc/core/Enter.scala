package dotty.tools.dotc
package core

import java.util

import dotty.tools.dotc.core.Contexts.Context
import Symbols._
import ast.Trees._
import Names.Name
import Types._
import StdNames.nme

/**
  * Creates symbols for declarations and enters them into a symbol table.
  */
class Enter {

  import ast.untpd._
  import Enter._

  val completers: util.Queue[Completer] = new util.ArrayDeque[Completer]()

  class LookupClassTemplateScope(classSym: ClassSymbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val classFoundSym = classSym.lookup(name)
      if (classFoundSym != NoSymbol)
        return LookedupSymbol(classFoundSym)
      if (name.isTypeName) {
        val tParamFoundSym = classSym.typeParams.lookup(name)
        if (tParamFoundSym != NoSymbol)
          return LookedupSymbol(tParamFoundSym)
      }
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => parentScope.lookup(name)
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupClassTemplateScope(classSym, imports, parentScope)
  }

  class LookupModuleTemplateScope(moduleSym: Symbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val foundSym = moduleSym.lookup(name)
      if (foundSym != NoSymbol)
        LookedupSymbol(foundSym)
      else {
        val ans = imports.lookup(name)
        ans match {
          case _: LookedupSymbol | _: IncompleteDependency => ans
          case _ => parentScope.lookup(name)
        }
      }
    }
    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupModuleTemplateScope(moduleSym, imports, parentScope)
  }

  class LookupDefDefScope(defSym: DefDefSymbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (name.isTypeName) {
        val tParamFoundSym = defSym.typeParams.lookup(name)
        if (tParamFoundSym != NoSymbol)
          return LookedupSymbol(tParamFoundSym)
      }
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => parentScope.lookup(name)
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupDefDefScope(defSym, imports, parentScope)
  }

  object RootPackageLookupScope extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val sym = context.definitions.rootPackage.lookup(name)
      if (sym == NoSymbol)
        NotFound
      else
        LookedupSymbol(sym)
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      sys.error("unsupported operation")
  }

  class LookupCompilationUnitScope(imports: ImportsLookupScope, pkgLookupScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => pkgLookupScope.lookup(name)
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupCompilationUnitScope(imports, pkgLookupScope)
  }

  def enterCompilationUnit(unit: CompilationUnit)(implicit context: Context): Unit = {
    val importsInCompilationUnit = new ImportsCollector(RootPackageLookupScope)
    val compilationUnitScope = new LookupCompilationUnitScope(importsInCompilationUnit.snapshot(), RootPackageLookupScope)
    enterTree(unit.untpdTree, context.definitions.rootPackage, new LookupScopeContext(importsInCompilationUnit, compilationUnitScope))
  }

  class PackageLookupScope(val pkgSym: Symbol, val parent: LookupScope, val imports: ImportsLookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val foundSym = pkgSym.lookup(name)
      if (foundSym != NoSymbol)
        LookedupSymbol(foundSym)
      else {
        val ans = imports.lookup(name)
        ans match {
          case _: LookedupSymbol | _: IncompleteDependency => ans
          case _ => parent.lookup(name)
        }
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new PackageLookupScope(pkgSym, parent, imports)
  }

  private class LookupScopeContext(imports: ImportsCollector, val parentScope: LookupScope) {
    private var cachedSimpleMemberLookupScope: LookupScope = parentScope
    def addImport(imp: Import): Unit = {
      cachedSimpleMemberLookupScope = null
      imports.append(imp)
    }
    def pushPackageLookupScope(pkgSym: PackageSymbol): LookupScopeContext = {
      val pkgLookupScope = new PackageLookupScope(pkgSym, parentScope, imports.snapshot())
      val pkgImports = new ImportsCollector(pkgLookupScope)
      new LookupScopeContext(pkgImports, pkgLookupScope)
    }
    def pushModuleLookupScope(modSym: ModuleSymbol): LookupScopeContext = {
      val moduleLookupScope = new LookupModuleTemplateScope(modSym, imports.snapshot(), parentScope)
      val moduleImports = new ImportsCollector(moduleLookupScope)
      new LookupScopeContext(moduleImports, moduleLookupScope)
    }
    def pushClassLookupScope(classSym: ClassSymbol): LookupScopeContext = {
      val classLookupScope = new LookupClassTemplateScope(classSym, imports.snapshot(), parentScope)
      // imports collector receives class lookup scope so the following construct is supported
      // class Bar { val y: String = "abc" }
      // class Foo { import x.y; val x: Bar = new Bar }
      // YES! Imports can have forward references in Scala (which is a little bit strange)
      val classImports = new ImportsCollector(classLookupScope)
      new LookupScopeContext(classImports, classLookupScope)
    }

    private def simpleMemberLookupScope(): LookupScope = {
      if (cachedSimpleMemberLookupScope != null)
        cachedSimpleMemberLookupScope
      else {
        cachedSimpleMemberLookupScope = parentScope.replaceImports(imports.snapshot())
        cachedSimpleMemberLookupScope
      }
    }

    def newValDefLookupScope(valDefSymbol: ValDefSymbol): LookupScope = simpleMemberLookupScope()
    def newDefDefLookupScope(defDefSymbol: DefDefSymbol): LookupScope =
      if (defDefSymbol.typeParams.size > 0) {
        new LookupDefDefScope(defDefSymbol, imports.snapshot(), parentScope)
      } else {
        simpleMemberLookupScope()
      }
  }

  private def enterTree(tree: Tree, owner: Symbol, parentLookupScopeContext: LookupScopeContext)(implicit context: Context): Unit = tree match {
    case PackageDef(ident, stats) =>
      val pkgSym = expandQualifiedPackageDeclaration(ident, owner)
      val lookupScopeContext = parentLookupScopeContext.pushPackageLookupScope(pkgSym)
      for (stat <- stats) enterTree(stat, pkgSym, lookupScopeContext)
    case imp: Import =>
      parentLookupScopeContext.addImport(imp)
    case ModuleDef(name, tmpl) =>
      val modClsSym = ClassSymbol(name)
      val modSym = ModuleSymbol(name, modClsSym)
      owner.addChild(modSym)
      val lookupScopeContext = parentLookupScopeContext.pushModuleLookupScope(modSym)
      locally {
        val completer = new TemplateMemberListCompleter(modClsSym, tmpl, lookupScopeContext.parentScope)
        completers.add(completer)
        modClsSym.completer = completer
      }
      locally {
        val completer = new ModuleCompleter(modSym)
        completers.add(completer)
        modSym.completer = completer
      }
      for (stat <- tmpl.body) enterTree(stat, modClsSym, lookupScopeContext)
    // class or trait
    case t@TypeDef(name, tmpl: Template) if t.isClassDef =>
      val classSym = new ClassSymbol(name)
      // t.tParams is empty for classes, the type parameters are accessible thorugh its primary constructor
      var remainingTparams = tmpl.constr.tparams
      var tParamIndex = 0
      while (remainingTparams.nonEmpty) {
        val tParam = remainingTparams.head
        // TODO: setup completers for TypeDef (resolving bounds, etc.)
        classSym.typeParams.enter(TypeParameterSymbol(tParam.name, tParamIndex))
        remainingTparams = remainingTparams.tail
        tParamIndex += 1
      }
      owner.addChild(classSym)
      assert(tmpl.constr.vparamss.size <= 1, "Multiple value parameter lists are not supported for class constructor")
      if (tmpl.constr.vparamss.size == 1) {
        var remainingVparams = tmpl.constr.vparamss.head
        while (remainingVparams.nonEmpty) {
          val vparam = remainingVparams.head
          remainingVparams = remainingVparams.tail
          // we're entering constructor parameter as a val declaration in a class
          // TODO: these parameters shouldn't be visible as members outside unless they are declared as vals
          // compare: class Foo(x: Int) vs class Foo(val x: Int)
          enterTree(vparam, classSym, parentLookupScopeContext)
        }
      }
      val lookupScopeContext = parentLookupScopeContext.pushClassLookupScope(classSym)
      val completer = new TemplateMemberListCompleter(classSym, tmpl, lookupScopeContext.parentScope)
      completers.add(completer)
      classSym.completer = completer
      for (stat <- tmpl.body) enterTree(stat, classSym, lookupScopeContext)
    // type alias or type member
    case TypeDef(name, _) =>
      val typeSymbol = new TypeDefSymbol(name)
      owner.addChild(typeSymbol)
    case t@ValDef(name, _, _) =>
      val valSym = new ValDefSymbol(name)
      val completer = new ValDefCompleter(valSym, t, parentLookupScopeContext.newValDefLookupScope(valSym))
      valSym.completer = completer
      owner.addChild(valSym)
    case t: DefDef =>
      val defSym = DefDefSymbol(t.name)
      var remainingTparams = t.tparams
      var tParamIndex = 0
      while (remainingTparams.nonEmpty) {
        val tParam = remainingTparams.head
        // TODO: setup completers for TypeDef (resolving bounds, etc.)
        defSym.typeParams.enter(TypeParameterSymbol(tParam.name, tParamIndex))
        remainingTparams = remainingTparams.tail
        tParamIndex += 1
      }
      val completer = new DefDefCompleter(defSym, t, parentLookupScopeContext.newDefDefLookupScope(defSym))
      defSym.completer = completer
      owner.addChild(defSym)
    case _ =>
  }

  private def expandQualifiedPackageDeclaration(pkgDecl: RefTree, owner: Symbol)(implicit ctx: Context): PackageSymbol =
    pkgDecl match {
    case Ident(name: Name) =>
      val lookedUp = owner.lookup(name)
      lookedUp match {
        case pkgSym: PackageSymbol => pkgSym
        case _ =>
          val pkgSym = new PackageSymbol(name)
          owner.addChild(pkgSym)
          pkgSym
      }
    case Select(qualifier: RefTree, name: Name) =>
      val qualPkg = expandQualifiedPackageDeclaration(qualifier, owner)
      val lookedUp = qualPkg.lookup(name)
      lookedUp match {
        case pkgSym: PackageSymbol => pkgSym
        case _ =>
          val pkgSym = new PackageSymbol(name)
          qualPkg.addChild(pkgSym)
          pkgSym
      }
  }

  def processJobQueue(memberListOnly: Boolean)(implicit ctx: Context): Int = {
    var steps = 0
    while (!completers.isEmpty) {
      steps += 1
      println(s"Step $steps/${steps+completers.size}")
      val completer = completers.remove()
      if (!completer.isCompleted) {
        val res = completer.complete()
        res match {
          case CompletedType(tpe: ClassInfoType) =>
            val classSym = tpe.clsSym
            classSym.info = tpe
            if (!memberListOnly)
              scheduleMembersCompletion(classSym)
          case CompletedType(tpe: ModuleInfoType) =>
            val modSym = tpe.modSym
            modSym.info = tpe
          case IncompleteDependency(sym: ClassSymbol) =>
            assert(sym.completer != null, sym.name)
            completers.add(sym.completer)
            completers.add(completer)
          case IncompleteDependency(sym: ModuleSymbol) =>
            assert(sym.completer != null, sym.name)
            completers.add(sym.completer)
            completers.add(completer)
          case IncompleteDependency(sym: ValDefSymbol) =>
            completers.add(sym.completer)
            completers.add(completer)
          case IncompleteDependency(sym: DefDefSymbol) =>
            completers.add(sym.completer)
            completers.add(completer)
          case CompletedType(tpe: MethodInfoType) =>
            val defDefSym = completer.sym.asInstanceOf[DefDefSymbol]
            defDefSym.info = tpe
          case CompletedType(tpe: ValInfoType) =>
            val valDefSym = completer.sym.asInstanceOf[ValDefSymbol]
            valDefSym.info = tpe
        }
      }
    }
    steps
  }

  private def scheduleMembersCompletion(sym: ClassSymbol): Unit = {
    var remainingDecls = sym.decls.toList
    while (remainingDecls.nonEmpty) {
      val decl = remainingDecls.head
      decl match {
        case defSym: DefDefSymbol => completers.add(defSym.completer)
        case valSym: ValDefSymbol => completers.add(valSym.completer)
        case _: ClassSymbol | _: ModuleSymbol =>
        case _: TypeDefSymbol =>
          println(s"Ignoring type def $decl in ${sym.name}")
      }
      remainingDecls = remainingDecls.tail
    }
  }

}

object Enter {
  import ast.untpd._

  sealed trait LookupAnswer
  case class LookedupSymbol(sym: Symbol) extends LookupAnswer
  case object NotFound extends LookupAnswer with CompletionResult

  sealed trait CompletionResult
  case class CompletedType(tpe: Type) extends CompletionResult

  case class IncompleteDependency(sym: Symbol) extends LookupAnswer with CompletionResult

  abstract class LookupScope {
    def lookup(name: Name)(implicit context: Context): LookupAnswer
    def replaceImports(imports: ImportsLookupScope): LookupScope
  }

  private class ImportCompleter(val importNode: Import) {
    private class ImportSelectorResolved(val termSym: Symbol, val typeSym: Symbol, val isWildcard: Boolean)
    private var exprSym0: Symbol = _
    private var resolvedSelectors: util.ArrayList[ImportSelectorResolved] = _
    private var isComplete: Boolean = false
    def complete(parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer = {
      isComplete = true
      val Import(expr, selectors) = importNode
      val exprAns = resolveSelectors(expr, parentLookupScope)
      exprAns match {
        case LookedupSymbol(exprSym) =>
          this.exprSym0 = exprSym
          if (exprSym.isComplete) {
            var remainingSelectors = selectors
            val resolvedSelectors: util.ArrayList[ImportSelectorResolved] = new util.ArrayList[ImportSelectorResolved]
            while (remainingSelectors.nonEmpty) {
              val Ident(name) = remainingSelectors.head
              remainingSelectors = remainingSelectors.tail
              if (name != nme.WILDCARD) {
                val termSym = lookupMember(exprSym, name)
                val typeSym = lookupMember(exprSym, name.toTypeName)
                if (termSym == NoSymbol && typeSym == NoSymbol)
                  return NotFound
                resolvedSelectors.add(new ImportSelectorResolved(termSym, typeSym, isWildcard = false))
              } else {
                resolvedSelectors.add(new ImportSelectorResolved(null, null, isWildcard = true))
              }
            }
            this.resolvedSelectors = resolvedSelectors
            LookedupSymbol(exprSym)
          } else IncompleteDependency(exprSym)
        case _ => exprAns
      }
    }
    def matches(name: Name)(implicit context: Context): Symbol = {
      assert(isComplete, s"the import node hasn't been completed: $importNode")
      var i = 0
      while (i < resolvedSelectors.size) {
        val selector = resolvedSelectors.get(i)
        val sym = if (selector.isWildcard) {
          exprSym0.info.lookup(name)
        } else {
          val termSym = selector.termSym
          val typeSym = selector.typeSym
          if (name.isTermName && termSym != null && termSym.name == name)
            termSym
          else if (typeSym != null && typeSym.name == name)
            typeSym
          else NoSymbol
        }
        // TODO: to support hiding with wildcard renames as in `import foo.bar.{abc => _}`, we would
        // need to continue scanning selectors and check for this shape of a rename
        if (sym != NoSymbol)
          return sym
        i += 1
      }
      NoSymbol
    }
  }

  private def lookupMember(sym: Symbol, name: Name)(implicit context: Context): Symbol = {
    assert(sym.isComplete, s"Can't look up a member $name in a symbol that is not completed yet: $sym")
    sym match {
      case clsSym: ClassSymbol => clsSym.info.lookup(name)
      case modSym: ModuleSymbol => modSym.info.lookup(name)
      case pkgSym: PackageSymbol => pkgSym.info.lookup(name)
      case valSym: ValDefSymbol => valSym.info.lookup(name)
    }
  }

  class ImportsCollector(parentLookupScope: LookupScope) {
    private val importCompleters: util.ArrayList[ImportCompleter] = new util.ArrayList[ImportCompleter]()
    def append(imp: Import): Unit = {
      importCompleters.add(new ImportCompleter(imp))
    }
    def snapshot(): ImportsLookupScope = {
      new ImportsLookupScope(importCompleters, parentLookupScope)()
    }
    def isEmpty: Boolean = importCompleters.isEmpty
  }

  class ImportsLookupScope(importCompleters: util.ArrayList[ImportCompleter], parentLookupScope: LookupScope)
                          (lastCompleterIndex: Int = importCompleters.size - 1) {
    private var allComplete: Boolean = false


    private def resolveImports()(implicit context: Context): Symbol = {
      var i: Int = 0
      while (i <= lastCompleterIndex) {
        val importsCompletedSoFar = new ImportsLookupScope(importCompleters, parentLookupScope)(lastCompleterIndex = i-1)
        importsCompletedSoFar.allComplete = true
        val parentLookupWithImports = parentLookupScope.replaceImports(importsCompletedSoFar)
        val impCompleter = importCompleters.get(i)
        impCompleter.complete(parentLookupWithImports) match {
          case _: LookedupSymbol =>
          case IncompleteDependency(sym) => return sym
          case NotFound => sys.error(s"couldn't resolve import ${impCompleter.importNode}")
        }
        i += 1
      }
      allComplete = true
      null
    }
    def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (!allComplete) {
        val sym = resolveImports()
        if (sym != null)
          return IncompleteDependency(sym)
      }
      var i = lastCompleterIndex
      while (i >= 0) {
        val completedImport = importCompleters.get(i)
        val sym = completedImport.matches(name)
        if (sym != NoSymbol)
          return LookedupSymbol(sym)
        i -= 1
      }
      NotFound
    }
  }

  abstract class Completer(val sym: Symbol) {
    def complete()(implicit context: Context): CompletionResult
    def isCompleted: Boolean
  }

  class ModuleCompleter(modSym: ModuleSymbol) extends Completer(modSym) {
    private var cachedInfo: ModuleInfoType = _
    override def complete()(implicit context: Context): CompletionResult = {
      if (cachedInfo != null)
        CompletedType(cachedInfo)
      else if (!modSym.clsSym.isComplete)
        IncompleteDependency(modSym.clsSym)
      else {
        cachedInfo = new ModuleInfoType(modSym, modSym.clsSym.info)
        CompletedType(cachedInfo)
      }
    }
    override def isCompleted: Boolean = cachedInfo != null
  }

  class TemplateMemberListCompleter(val clsSym: ClassSymbol, tmpl: Template, val lookupScope: LookupScope) extends Completer(clsSym) {
    private var cachedInfo: ClassInfoType = _
    def complete()(implicit context: Context): CompletionResult = {
      val resolvedParents = new util.ArrayList[Type]()
      var remainingParents = tmpl.parents
      while (remainingParents.nonEmpty) {
        val parent = remainingParents.head
        val resolved = resolveTypeTree(parent, lookupScope)
        resolved match {
          case CompletedType(tpe) => resolvedParents.add(tpe)
          case res: IncompleteDependency => return res
        }
        remainingParents = remainingParents.tail
      }
      val info = new ClassInfoType(clsSym)
      var i = 0
      while (i < resolvedParents.size()) {
        val parentType = resolvedParents.get(i)
        if (parentType.typeSymbol == NoSymbol)
          println("ooops")
        val parentSym = parentType.typeSymbol.asInstanceOf[ClassSymbol]
        if (parentSym.name.toString == "Main")
          println("We have Main as parent!")
        val parentInfo = if (parentSym.info != null) parentSym.info else
          return IncompleteDependency(parentSym)
        parentType match {
          case at: AppliedType =>
            import TypeOps.{TypeParamMap, deriveMemberOfAppliedType}
            val typeParams = at.typeSymbol.asInstanceOf[ClassSymbol].typeParams
            val typeParamMap = new TypeParamMap(typeParams)
            for (m <- parentInfo.members.iterator) {
              if (!m.isComplete)
                return IncompleteDependency(m)
              val derivedInheritedMember = deriveMemberOfAppliedType(m, at, typeParamMap)
              info.members.enter(derivedInheritedMember)
            }
          case other =>
            info.members.enterAll(parentInfo.members)
        }
        i += 1
      }
      info.members.enterAll(clsSym.decls)
      cachedInfo = info
      CompletedType(info)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  class DefDefCompleter(sym: DefDefSymbol, defDef: DefDef, val lookupScope: LookupScope) extends Completer(sym) {
    private var cachedInfo: MethodInfoType = _
    def complete()(implicit context: Context): CompletionResult = {
      val paramTypes = {
        var remainingVparamGroups = defDef.vparamss
        val resolvedParamTypeGroups = new util.ArrayList[util.ArrayList[Type]]()
        while(remainingVparamGroups.nonEmpty) {
          var remainingVparams = remainingVparamGroups.head
          remainingVparamGroups = remainingVparamGroups.tail
          val resolvedParamTypes = new util.ArrayList[Type]()
          while (remainingVparams.nonEmpty) {
            val vparam = remainingVparams.head
            val resolvedType = resolveTypeTree(vparam.tpt, lookupScope)
            resolvedType match {
              case CompletedType(tpe) => resolvedParamTypes.add(tpe)
              case res: IncompleteDependency => return res
              case NotFound => sys.error(s"Couldn't resolve ${vparam.tpt}")
            }
            remainingVparams = remainingVparams.tail
          }
          resolvedParamTypeGroups.add(resolvedParamTypes)
        }
        asScalaList2(resolvedParamTypeGroups)
      }
      val resultType: Type = if (defDef.tpt.isEmpty) InferredTypeMarker else {
        val resolvedType = resolveTypeTree(defDef.tpt, lookupScope)
        resolvedType match {
          case CompletedType(tpe) => tpe
          case res: IncompleteDependency => return res
          case NotFound => sys.error(s"Couldn't resolve ${defDef.tpt}")
        }
      }
      val info = MethodInfoType(sym, paramTypes, resultType)
      cachedInfo = info
      CompletedType(info)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  class ValDefCompleter(sym: ValDefSymbol, valDef: ValDef, val lookupScope: LookupScope) extends Completer(sym) {
    private var cachedInfo: ValInfoType = _
    def complete()(implicit context: Context): CompletionResult = try {
      val resultType: Type = if (valDef.tpt.isEmpty) InferredTypeMarker else {
        val resolvedType = resolveTypeTree(valDef.tpt, lookupScope)
        resolvedType match {
          case CompletedType(tpe) => tpe
          case res: IncompleteDependency => return res
          case NotFound => sys.error(s"Couldn't resolve ${valDef.tpt}")
        }
      }
      val info = ValInfoType(sym, resultType)
      cachedInfo = info
      CompletedType(info)
    } catch {
      case ex: Exception =>
        throw new RuntimeException(s"Error while completing $valDef", ex)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  private def resolveSelectors(t: Tree, parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer =
    t match {
      case Ident(identName) => parentLookupScope.lookup(identName)
      case Select(qual, selName) =>
        val ans = resolveSelectors(qual, parentLookupScope)
        ans match {
          case LookedupSymbol(qualSym) =>
            if (qualSym.isComplete) {
              val selSym = qualSym.info.lookup(selName)
              if (selSym != NoSymbol)
                LookedupSymbol(selSym)
              else
                NotFound
            } else IncompleteDependency(qualSym)
          case _ => ans
        }
      case _ => sys.error(s"Unhandled tree $t at ${t.pos}")
    }

  private def resolveTypeTree(t: Tree, parentLookupScope: LookupScope)(implicit context: Context): CompletionResult = t match {
    case AppliedTypeTree(tpt, args) =>
      val resolvedTpt = resolveTypeTree(tpt, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case uncompleted => return uncompleted
      }
      var remainingArgs = args
      val resolvedArgs = new util.ArrayList[Type]()
      while (remainingArgs.nonEmpty) {
        val resolvedArg = resolveTypeTree(remainingArgs.head, parentLookupScope)
        resolvedArg match {
          case CompletedType(argTpe) => resolvedArgs.add (argTpe)
          case _ => return resolvedArg
        }
        remainingArgs = remainingArgs.tail
      }
      CompletedType(AppliedType(resolvedTpt, resolvedArgs.toArray(new Array[Type](resolvedArgs.size))))
    // ParentClass(foo) is encoded as a constructor call with a tree of shape
    // Apply(Select(New(Ident(ParentClass)),<init>),List(Ident(foo)))
    // we want to extract the Ident(ParentClass)
    case Apply(Select(New(tp), nme.CONSTRUCTOR), _) =>
      resolveTypeTree(tp, parentLookupScope)
    case Parens(t2) => resolveTypeTree(t2, parentLookupScope)
    case Function(args, body) =>
      assert(args.size == 1, s"Only Function1 is supported")
      val resolvedArg = resolveTypeTree(args.head, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val resolvedBody = resolveTypeTree(body, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      import Decorators._
      val LookedupSymbol(function1Type) = parentLookupScope.lookup("Function1".toTypeName)
      CompletedType(AppliedType(SymRef(function1Type), Array(resolvedArg, resolvedBody)))
    // TODO: I ignore a star indicator of a repeated parameter as it's not essential and fairly trivial to deal with
    case PostfixOp(ident, nme.raw.STAR) =>
      resolveTypeTree(ident, parentLookupScope)
    // TODO: we horribly ignore tuples for now
    case Tuple(trees) =>
      resolveTypeTree(trees.head, parentLookupScope)
    // TODO: we ignore by name argument `=> T` and resolve it as `T`
    case ByNameTypeTree(res) =>
      resolveTypeTree(res, parentLookupScope)
    // idnet or select?
    case other =>
      val resolvedSel = resolveSelectors(other, parentLookupScope)
      resolvedSel match {
        case LookedupSymbol(sym) => CompletedType(SymRef(sym))
        case NotFound =>
          sys.error(s"Can't resolve selector $other")
        case incomplete: IncompleteDependency => incomplete
      }
  }

  private def asScalaList[T](javaList: util.ArrayList[T]): List[T] = {
    var i = javaList.size() - 1
    var res: List[T] = Nil
    while (i >= 0) {
      res = javaList.get(i) :: res
      i -= 1
    }
    res
  }

  private def asScalaList2[T](javaList: util.ArrayList[util.ArrayList[T]]): List[List[T]] = {
    var i = javaList.size() - 1
    var res: List[List[T]] = Nil
    while (i >= 0) {
      val innerList = asScalaList(javaList.get(i))
      res = innerList :: res
      i -= 1
    }
    res
  }
}

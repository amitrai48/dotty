package dotty.tools.dotc
package transform

import core.Phases._
import core.DenotTransformers._
import core.Denotations._
import core.SymDenotations._
import core.Symbols._
import core.Contexts._
import core.Types._
import core.Names._
import core.StdNames._
import core.NameOps._
import core.Decorators._
import core.Constants._
import typer.NoChecking
import typer.ProtoTypes._
import typer.ErrorReporting._
import core.transform.Erasure._
import core.Decorators._
import dotty.tools.dotc.ast.{Trees, tpd, untpd}
import ast.Trees._
import scala.collection.mutable.ListBuffer
import dotty.tools.dotc.core.Flags
import ValueClasses._
import TypeUtils._

class Erasure extends Phase with DenotTransformer { thisTransformer =>

  override def phaseName: String = "erasure"

  /** List of names of phases that should precede this phase */
  override def runsAfter: Set[String] = Set("typeTestsCasts"/*, "intercepted"*/, "splitter", "elimRepeated")

  def transform(ref: SingleDenotation)(implicit ctx: Context): SingleDenotation = ref match {
    case ref: SymDenotation =>
      assert(ctx.phase == this, s"transforming $ref at ${ctx.phase}")
      if (ref.symbol eq defn.ObjectClass) {
        // Aftre erasure, all former Any members are now Object members
        val ClassInfo(pre, _, ps, decls, selfInfo) = ref.info
        val extendedScope = decls.cloneScope
        defn.AnyClass.classInfo.decls.foreach(extendedScope.enter)
        ref.copySymDenotation(
          info = transformInfo(ref.symbol,
              ClassInfo(pre, defn.ObjectClass, ps, extendedScope, selfInfo))
        )
      }
      else {
        val oldOwner = ref.owner
        val newOwner = if (oldOwner eq defn.AnyClass) defn.ObjectClass else oldOwner
        val oldInfo = ref.info
        val newInfo = transformInfo(ref.symbol, oldInfo)
        if ((oldOwner eq newOwner) && (oldInfo eq newInfo)) ref
        else {
          assert(!ref.is(Flags.PackageClass), s"trans $ref @ ${ctx.phase} oldOwner = $oldOwner, newOwner = $newOwner, oldInfo = $oldInfo, newInfo = $newInfo ${oldOwner eq newOwner} ${oldInfo eq newInfo}")
          ref.copySymDenotation(owner = newOwner, info = newInfo)
        }
      }
    case ref =>
      ref.derivedSingleDenotation(ref.symbol, erasure(ref.info))
  }

  val eraser = new Erasure.Typer

  def run(implicit ctx: Context): Unit = {
    val unit = ctx.compilationUnit
    unit.tpdTree = eraser.typedExpr(unit.tpdTree)(ctx.fresh.setPhase(this.next))
  }
}

object Erasure {

  import tpd._

  object Boxing {

    def isUnbox(sym: Symbol)(implicit ctx: Context) =
      sym.name == nme.unbox && (defn.ScalaBoxedClasses contains sym.owner)

    def isBox(sym: Symbol)(implicit ctx: Context) =
      sym.name == nme.box && (defn.ScalaValueClasses contains sym.owner)

    def boxMethod(cls: ClassSymbol)(implicit ctx: Context) =
      cls.linkedClass.info.member(nme.box).symbol
    def unboxMethod(cls: ClassSymbol)(implicit ctx: Context) =
      cls.linkedClass.info.member(nme.unbox).symbol

    /** Isf this tree is an unbox operation which can be safely removed
     *  when enclosed in a box, the unboxed argument, otherwise EmptyTree.
     *  Note that one can't always remove a Box(Unbox(x)) combination because the
     *  process of unboxing x may lead to throwing an exception.
     *  This is important for specialization: calls to the super constructor should not box/unbox specialized
     *  fields (see TupleX). (ID)
     */
    private def safelyRemovableUnboxArg(tree: Tree)(implicit ctx: Context): Tree = tree match {
      case Apply(fn, arg :: Nil)
      if isUnbox(fn.symbol) && (defn.ScalaBoxedClasses contains arg.tpe.widen.typeSymbol) =>
        arg
      case _ =>
        EmptyTree
    }

    def constant(tree: Tree, const: Tree)(implicit ctx: Context) =
      if (isPureExpr(tree)) Block(tree :: Nil, const) else const

    final def box(tree: Tree, target: => String = "")(implicit ctx: Context): Tree = ctx.traceIndented(i"boxing ${tree.showSummary}: ${tree.tpe} into $target") {
      tree.tpe.widen match {
        case ErasedValueType(clazz, _) =>
          New(clazz.typeRef, cast(tree, underlyingOfValueClass(clazz)) :: Nil) // todo: use adaptToType?
        case tp =>
          val cls = tp.classSymbol
          if (cls eq defn.UnitClass) constant(tree, ref(defn.BoxedUnit_UNIT))
          else if (cls eq defn.NothingClass) tree // a non-terminating expression doesn't need boxing
          else {
            assert(cls ne defn.ArrayClass)
            val arg = safelyRemovableUnboxArg(tree)
            if (arg.isEmpty) ref(boxMethod(cls.asClass)).appliedTo(tree)
            else {
              ctx.log(s"boxing an unbox: ${tree.symbol} -> ${arg.tpe}")
              arg
            }
          }
      }
    }

    def unbox(tree: Tree, pt: Type)(implicit ctx: Context): Tree = ctx.traceIndented(i"unboxing ${tree.showSummary}: ${tree.tpe} as a $pt") {
      pt match {
        case ErasedValueType(clazz, underlying) =>
          val tree1 =
            if ((tree.tpe isRef defn.NullClass) && underlying.isPrimitiveValueType)
              // convert `null` directly to underlying type, as going
              // via the unboxed type would yield a NPE (see SI-5866)
              unbox(tree, underlying)
            else
              adaptToType(tree, clazz.typeRef)
                .select(valueClassUnbox(clazz))
                .appliedToNone
          cast(tree1, pt)
        case _ =>
          val cls = pt.classSymbol
          if (cls eq defn.UnitClass) constant(tree, Literal(Constant(())))
          else {
            assert(cls ne defn.ArrayClass)
            ref(unboxMethod(cls.asClass)).appliedTo(tree)
          }
      }
    }

    /** Generate a synthetic cast operation from tree.tpe to pt.
     */
    def cast(tree: Tree, pt: Type)(implicit ctx: Context): Tree = {
      // TODO: The commented out assertion fails for tailcall/t6574.scala
      //       Fix the problem and enable the assertion.
      // assert(!pt.isInstanceOf[SingletonType], pt)
      if (pt isRef defn.UnitClass) unbox(tree, pt)
      else (tree.tpe, pt) match {
        case (defn.ArrayType(treeElem), defn.ArrayType(ptElem))
        if treeElem.widen.isPrimitiveValueType && !ptElem.isPrimitiveValueType =>
          // See SI-2386 for one example of when this might be necessary.
          cast(runtimeCall(nme.toObjectArray, tree :: Nil), pt)
        case _ =>
          ctx.log(s"casting from ${tree.showSummary}: ${tree.tpe.show} to ${pt.show}")
          tree.asInstance(pt)
      }
    }

    /** Adaptation of an expression `e` to an expected type `PT`, applying the following
     *  rewritings exhaustively as long as the type of `e` is not a subtype of `PT`.
     *
     *    e -> e()           if `e` appears not as the function part of an application
     *    e -> box(e)        if `e` is of erased value type
     *    e -> unbox(e, PT)  otherwise, if `PT` is an erased value type
     *    e -> box(e)        if `e` is of primitive type and `PT` is not a primitive type
     *    e -> unbox(e, PT)  if `PT` is a primitive type and `e` is not of primitive type
     *    e -> cast(e, PT)   otherwise
     */
    def adaptToType(tree: Tree, pt: Type)(implicit ctx: Context): Tree = {
      def makeConformant(tpw: Type): Tree = tpw match {
        case MethodType(Nil, _) =>
          adaptToType(tree.appliedToNone, pt)
        case _ =>
          if (tpw.isErasedValueType)
            adaptToType(box(tree), pt)
          else if (pt.isErasedValueType)
            adaptToType(unbox(tree, pt), pt)
          else if (tpw.isPrimitiveValueType && !pt.isPrimitiveValueType)
            adaptToType(box(tree), pt)
          else if (pt.isPrimitiveValueType && !tpw.isPrimitiveValueType)
            adaptToType(unbox(tree, pt), pt)
          else
            cast(tree, pt)
      }
      if ((pt eq AnyFunctionProto) || tree.tpe <:< pt) tree
      else makeConformant(tree.tpe.widen)
    }
  }

  class Typer extends typer.ReTyper with NoChecking {
    import Boxing._

    def erasedType(tree: untpd.Tree)(implicit ctx: Context): Type = erasure(tree.typeOpt)

    override def promote(tree: untpd.Tree)(implicit ctx: Context): tree.ThisTree[Type] = {
      assert(tree.hasType)
      val erased = erasedType(tree)(ctx.withPhase(ctx.erasurePhase))
      ctx.log(s"promoting ${tree.show}: ${erased.showWithUnderlying()}")
      tree.withType(erased)
    }

    /** Type check select nodes, applying the following rewritings exhaustively
     *  on selections `e.m`.
     *
     *      e.m1 -> e.m2        if `m1` is a member of Any or AnyVal and `m2` is
     *                          the same-named member in Object.
     *      e.m -> box(e).m     if `e` is primitive and `m` is a member or a reference class
     *                          or `e` has an erased value class type.
     *      e.m -> unbox(e).m   if `e` is not primitive and `m` is a member of a primtive type.
     *
     *  Additionally, if the type of `e` does not derive from the type `OT` of the owner of `m`,
     *  the following rewritings are performed, where `ET` is the erased type of the selection's
     *  original qualifier expression.
     *
     *      e.m -> cast(OT).m   if `m` is not an array operation
     *      e.m -> cast(ET).m   if `m` is an array operation and `ET` is an array type
     *      e.m -> runtime.array_m(e)
     *                          if `m` is an array operation and `ET` is Object
     */
    override def typedSelect(tree: untpd.Select, pt: Type)(implicit ctx: Context): Tree = {
      val sym = tree.symbol
      assert(sym.exists, tree.show)

      def select(qual: Tree, sym: Symbol): Tree =
        untpd.cpy.Select(tree)(qual, sym.name) withType qual.tpe.select(sym)

      def selectArrayMember(qual: Tree, erasedPre: Type) =
        if (erasedPre isRef defn.ObjectClass) runtimeCall(tree.name.genericArrayOp, qual :: Nil)
        else recur(cast(qual, erasedPre))

      def recur(qual: Tree): Tree = {
        val qualIsPrimitive = qual.tpe.widen.isPrimitiveValueType
        val symIsPrimitive = sym.owner.isPrimitiveValueClass
        if ((sym.owner eq defn.AnyClass) || (sym.owner eq defn.AnyValClass))
          select(qual, defn.ObjectClass.info.decl(sym.name).symbol)
        else if (qualIsPrimitive && !symIsPrimitive || qual.tpe.isErasedValueType)
          recur(box(qual))
        else if (!qualIsPrimitive && symIsPrimitive)
          recur(unbox(qual, sym.owner.typeRef))
        else if (qual.tpe.derivesFrom(sym.owner) || qual.isInstanceOf[Super])
          select(qual, sym)
        else if (sym.owner eq defn.ArrayClass)
          selectArrayMember(qual, erasure(tree.qualifier.tpe))
        else
          recur(cast(qual, sym.owner.typeRef))
      }

      recur(typed(tree.qualifier, AnySelectionProto))
    }

    override def typedTypeApply(tree: untpd.TypeApply, pt: Type)(implicit ctx: Context) = {
      val TypeApply(fun, args) = tree
      val fun1 = typedExpr(fun, pt)
      fun1.tpe.widen match {
        case funTpe: PolyType =>
          val args1 = args.mapconserve(typedType(_))
          untpd.cpy.TypeApply(tree)(fun1, args1).withType(funTpe.instantiate(args1.tpes))
        case _ => fun1
      }
    }

    override def typedApply(tree: untpd.Apply, pt: Type)(implicit ctx: Context): Tree = {
      val Apply(fun, args) = tree
      fun match {
        case fun: Apply =>
          typedApply(fun, pt)(ctx.fresh.setTree(tree))
        case _ =>
          def nextOuter(ctx: Context): Context =
            if (ctx.outer.tree eq tree) nextOuter(ctx.outer) else ctx.outer
          def contextArgs(tree: untpd.Apply)(implicit ctx: Context): List[untpd.Tree] =
            ctx.tree match {
              case enclApp @ Apply(enclFun, enclArgs) if enclFun eq tree =>
                enclArgs ++ contextArgs(enclApp)(nextOuter(ctx))
              case _ =>
                Nil
            }
          val allArgs = args ++ contextArgs(tree)
          val fun1 = typedExpr(fun, AnyFunctionProto)
          fun1.tpe.widen match {
            case mt: MethodType =>
              val allArgs1 = allArgs.zipWithConserve(mt.paramTypes)(typedExpr)
              untpd.cpy.Apply(tree)(fun1, allArgs1) withType mt.resultType
            case _ =>
              throw new MatchError(i"tree $tree has unexpected type of function ${fun1.tpe.widen}, was ${fun.typeOpt.widen}")
          }
      }
    }

    override def typedTypeTree(tree: untpd.TypeTree, pt: Type)(implicit ctx: Context): TypeTree =
      promote(tree)

    override def ensureNoLocalRefs(block: Block, pt: Type, forcedDefined: Boolean = false)(implicit ctx: Context): Tree =
      block // optimization, no checking needed, as block symbols do not change.

    override def typedDefDef(ddef: untpd.DefDef, sym: Symbol)(implicit ctx: Context) = {
      val ddef1 = untpd.cpy.DefDef(ddef)(
        tparams = Nil,
        vparamss = if (ddef.vparamss.isEmpty) Nil :: Nil else ddef.vparamss,
        tpt = // keep UnitTypes intact in result position
          if (ddef.tpt.typeOpt isRef defn.UnitClass) untpd.TypeTree(defn.UnitType) withPos ddef.tpt.pos
          else ddef.tpt)
      super.typedDefDef(ddef1, sym)
    }

    override def typedTypeDef(tdef: untpd.TypeDef, sym: Symbol)(implicit ctx: Context) =
      EmptyTree

    override def typedStats(stats: List[untpd.Tree], exprOwner: Symbol)(implicit ctx: Context): List[Tree] = {
      val statsFlatten = Trees.flatten(stats)
      val stats1 = super.typedStats(statsFlatten, exprOwner)

      if (ctx.owner.isClass) stats1:::addBridges(statsFlatten, stats1)(ctx) else stats1
    }

    // this implementation doesn't check for bridge clashes with value types!
    def addBridges(oldStats: List[untpd.Tree], newStats: List[tpd.Tree])(implicit ctx: Context): List[tpd.Tree] = {
      val beforeCtx = ctx.withPhase(ctx.erasurePhase)
      def traverse(after: List[Tree], before: List[untpd.Tree],
                   emittedBridges: ListBuffer[tpd.DefDef] = ListBuffer[tpd.DefDef]()): List[tpd.DefDef] = {
        after match {
          case Nil => emittedBridges.toList
          case (member: DefDef) :: newTail =>
            before match {
              case Nil => emittedBridges.toList
              case (oldMember: untpd.DefDef) :: oldTail =>
                val oldSymbol = oldMember.symbol(beforeCtx)
                val newSymbol = member.symbol(ctx)
                assert(oldSymbol.name(beforeCtx) == newSymbol.name,
                  s"${oldSymbol.name(beforeCtx)} bridging with ${newSymbol.name}")
                val newOverridden = oldSymbol.denot.allOverriddenSymbols.toSet // TODO: clarify new <-> old in a comment; symbols are swapped here
                val oldOverridden = newSymbol.allOverriddenSymbols(beforeCtx).toSet // TODO: can we find a more efficient impl? newOverridden does not have to be a set!
                val neededBridges = oldOverridden -- newOverridden

                var minimalSet = Set[Symbol]()
                // compute minimal set of bridges that are needed:
                for (bridge <- neededBridges) {
                  val isRequired = minimalSet.forall(nxtBridge => !(bridge.info =:= nxtBridge.info))

                  if (isRequired) {
                    // check for clashes
                    val clash: Option[Symbol] = oldSymbol.owner.decls.lookupAll(bridge.name).find {
                      sym =>
                        (sym.name eq bridge.name) && sym.info.widen =:= bridge.info.widen
                    }.orElse(
                        emittedBridges.find(stat => (stat.name == bridge.name) && stat.tpe.widen =:= bridge.info.widen)
                          .map(_.symbol)
                      )
                    clash match {
                      case Some(cl) =>
                        ctx.error(i"bridge for method ${newSymbol.showLocated(beforeCtx)} of type ${newSymbol.info(beforeCtx)}\n" +
                          i"clashes with ${cl.symbol.showLocated(beforeCtx)} of type ${cl.symbol.info(beforeCtx)}\n" +
                          i"both have same type after erasure: ${bridge.symbol.info}")
                      case None => minimalSet += bridge
                    }
                  }
                }

                val bridgeImplementations = minimalSet.map {
                  sym => makeBridgeDef(member, sym)(ctx)
                }
                emittedBridges ++= bridgeImplementations
                traverse(newTail, oldTail)
              case notADefDef :: oldTail =>
                traverse(after, oldTail)
            }
          case notADefDef :: newTail =>
            traverse(newTail, before)
        }
      }

      traverse(newStats, oldStats)
    }

    def makeBridgeDef(newDef: tpd.DefDef, parentSym: Symbol)(implicit ctx: Context): tpd.DefDef = {
      def error(reason: String) = {
        assert(false, s"failure creating bridge from ${newDef.symbol} to ${parentSym}, reason: $reason")
        ???
      }
      val bridge = ctx.newSymbol(newDef.symbol.owner,
        parentSym.name, parentSym.flags | Flags.Bridge, parentSym.info, coord = newDef.symbol.owner.coord).asTerm
      bridge.enteredAfter(ctx.phase.prev.asInstanceOf[DenotTransformer]) // this should be safe, as we're executing in context of next phase
      ctx.debuglog(s"generating bridge from ${newDef.symbol} to $bridge")

      val sel: Tree = This(newDef.symbol.owner.asClass).select(newDef.symbol.termRef)

      val resultType = bridge.info.widen.resultType
      tpd.DefDef(bridge, { paramss: List[List[tpd.Tree]] =>
          val rhs = paramss.foldLeft(sel)((fun, vparams) =>
            fun.tpe.widen match {
              case MethodType(names, types) => Apply(fun, (vparams, types).zipped.map(adapt(_, _, untpd.EmptyTree)))
              case a => error(s"can not resolve apply type $a")

            })
          adapt(rhs, resultType)
      })
    }

    override def adapt(tree: Tree, pt: Type, original: untpd.Tree)(implicit ctx: Context): Tree =
      ctx.traceIndented(i"adapting ${tree.showSummary}: ${tree.tpe} to $pt", show = true) {
        assert(ctx.phase == ctx.erasurePhase.next, ctx.phase)
        if (tree.isEmpty) tree else adaptToType(tree, pt)
    }
  }
}

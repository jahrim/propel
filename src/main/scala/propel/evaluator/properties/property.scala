package propel
package evaluator
package properties

import ast.*

enum PropertyType:
  case Relation
  case Function

trait PropertyChecking:
  val varA = Var(Symbol("a"))
  val varB = Var(Symbol("b"))
  val varC = Var(Symbol("c"))

  val propertyType: PropertyType

  def prepare(ident0: Symbol, ident1: Symbol, expr: Term): (Term, Equalities)
  def control(expr: Term, equalities: Equalities, nested: Boolean): (Term, Equalities, Symbolic.Control)
  def check(result: Symbolic.Result): Boolean

object PropertyChecking:
  trait FunctionEqualResult extends PropertyChecking:
    val propertyType = PropertyType.Function
    val equalDataConstructor = Constructor(Symbol("≟"))

    private def extensional(expr0: Term, expr1: Term): (Term, Term) = (expr0, expr1) match
      case (Abs(_, ident0, _, expr0), Abs(_, ident1, _, expr1)) =>
        extensional(expr0, subst(expr1, Map(ident1 -> Var(ident0))))
      case exprs =>
        exprs

    def control(expr: Term, equalities: Equalities, nested: Boolean) = expr match
      case Data(`equalDataConstructor`, List(arg0, arg1)) if !nested =>
        val (extensionalArg0, extensionalArg1) = extensional(arg0, arg1)
        (Data(expr)(`equalDataConstructor`, List(extensionalArg0, extensionalArg1)), Equalities.empty, Symbolic.Control.Continue)
      case _ =>
        (expr, Equalities.empty, Symbolic.Control.Continue)

    def check(result: Symbolic.Result) = result.reductions forall {
      case Symbolic.Reduction(Data(`equalDataConstructor`, List(arg0, arg1)), _, _) => arg0 == arg1
      case _ => false
    }

  trait RelationTrueResult extends PropertyChecking:
    val propertyType = PropertyType.Relation

    def control(expr: Term, equalities: Equalities, nested: Boolean) =
      (expr, Equalities.empty, Symbolic.Control.Continue)

    def check(result: Symbolic.Result) = result.reductions forall {
      case Symbolic.Reduction(Data(Constructor.True, _), _, _) => true
      case _ => false
    }

  trait Simple extends PropertyChecking:
    def deriveSimple(equalities: Equalities): PartialFunction[(Term, Term), List[Equalities]]

  trait Compound extends PropertyChecking:
    def deriveCompound(equalities: Equalities): PartialFunction[((Term, Term), (Term, Term)), List[Equalities]]

  trait Normal extends PropertyChecking:
    def normalize(equalities: Equalities): PartialFunction[Term, Term]
end PropertyChecking

package propel
package evaluator

import ast.*
import typer.*

enum Equality:
  case Equal
  case Indeterminate
  case Unequal

object Equality:
  inline def min(inline equality0: Equality, inline equality1: Equality) =
    def equality = equality1
    (equality0: equality0.type @unchecked) match
      case Unequal => Unequal
      case Equal => equality
      case Indeterminate => if equality == Unequal then Unequal else Indeterminate

  inline def max(inline equality0: Equality, inline equality1: Equality) =
    def equality = equality1
    (equality0: equality0.type @unchecked) match
      case Equal => Equal
      case Unequal => equality
      case Indeterminate => if equality == Equal then Equal else Indeterminate

  inline def neg(inline equality: Equality) =
    (equality: equality.type @unchecked) match
      case Equal => Unequal
      case Unequal => Equal
      case Indeterminate => Indeterminate
end Equality

case class Equalities private (pos: Map[Term, Term], neg: Set[Map[Term, Term]]):
  override val hashCode = scala.util.hashing.MurmurHash3.productHash(this)
  override def equals(other: Any) = other match
    case other: Equalities => eq(other) || hashCode == other.hashCode && pos == other.pos && neg == other.neg && other.canEqual(this)
    case _ => false

  private def contains(outer: Term, inner: Term): Boolean =
    equivalent(outer, inner) || {
    outer match
      case Abs(_, _, _, expr) => contains(expr, inner)
      case App(_, expr, arg) => contains(expr, inner) || contains(arg, inner)
      case TypeAbs(_, expr) => contains(expr, inner)
      case TypeApp(expr, _) => contains(expr, inner)
      case Data(_, args) => args exists { contains(_, inner) }
      case Var(_) => false
      case Cases(scrutinee, cases) => contains(scrutinee, inner) || (cases exists { (_, expr) => contains(expr, inner) })
    }

  private given Ordering[Term] =
    case (expr0, expr1) if contains(expr0, expr1) => -1
    case (expr0, expr1) if contains(expr1, expr0) => 1
    case (expr0: Var, expr1: Var) => termOrdering.compare(expr0, expr1)
    case (expr0: Var, expr1) => -1
    case (expr0, expr1: Var) => 1
    case (expr0, expr1) => termOrdering.compare(expr0, expr1)

  def equal(expr0: Term, expr1: Term): Equality =
    def equal(expr0: Term, expr1: Term): (Equality, List[(Term, Term)]) =
      pos.getOrElse(expr0, expr0) -> pos.getOrElse(expr1, expr1) match
        case (expr0: (Abs | TypeAbs | Cases)) -> (expr1: (Abs | TypeAbs | Cases)) if equivalent(expr0, expr1) =>
          Equality.Equal -> List.empty
        case terms @ App(_, expr0, arg0) -> App(_, expr1, arg1) =>
          equal(expr0, expr1) match
            case Equality.Unequal -> _ => Equality.Unequal -> List.empty
            case equality0 -> exprs0 =>
              val equality1 -> exprs1 = equal(arg0, arg1)
              Equality.min(equality0, equality1) -> (terms :: exprs0 ++ exprs1)
        case terms @ TypeApp(expr0, tpe0) -> TypeApp(expr1, tpe1) if equivalent(tpe0, tpe1) =>
          val equality -> exprs = equal(expr0, expr1)
          equality -> (terms :: exprs)
        case terms @ Data(ctor0, args0) -> Data(ctor1, args1) =>
          if ctor0 == ctor1 && args0.sizeCompare(args1) == 0 then
            if args0.isEmpty then
              Equality.Equal -> List(terms)
            else
              val (equality, exprs) = equal(args0.head, args1.head)
              (args0.tail zip args1.tail).foldLeft(equality -> (terms :: exprs)) {
                case (Equality.Unequal -> _, _) =>
                  Equality.Unequal -> List.empty
                case (equality0 -> exprs0, arg0 -> arg1) =>
                  val equality1 -> exprs1 = equal(arg0, arg1)
                  Equality.min(equality0, equality1) -> (exprs0 ++ exprs1)
              }
          else
            Equality.Unequal -> List.empty
        case terms @ Var(ident0) -> Var(ident1) if ident0 == ident1 =>
          Equality.Equal -> List(terms)
        case terms =>
          Equality.Indeterminate -> List(terms)

    equal(expr0, expr1) match
      case (Equality.Unequal, _) =>
        Equality.Unequal
      case (equality, exprs) =>
        val unequal = neg exists {
          _ forall { (ne0, ne1) =>
            exprs exists { (expr0, expr1) =>
              equal(ne0, expr0) match
                case (Equality.Equal, _) =>
                  val equality -> _ = equal(ne1, expr1)
                  equality == Equality.Equal
                case _ =>
                  equal(ne0, expr1) match
                    case (Equality.Equal, _) =>
                      val equality -> _ = equal(ne1, expr0)
                      equality == Equality.Equal
                    case _ =>
                      false
            }
          }
        }
        if unequal then Equality.Unequal else equality
  end equal

  def contradictionIndeducible: Boolean =
    def isVar(expr: Term): Boolean = expr match
      case Var(_) => true
      case _ => false

    def isInductive(expr: Term): Boolean = expr match
      case Var(_) => true
      case Data(_, args) => args forall isInductive
      case _ => false

    def hasSameType(expr0: Term, expr1: Term) =
      expr0.termType exists { tpe => expr1.termType exists { equivalent(tpe, _) } }

    (neg forall { _ forall { (expr0, expr1) => isInductive(expr0) && isInductive(expr1) } }) &&
    (pos forall { (expr0, expr1) =>
      isVar(expr0) || isVar(expr1) ||
      hasSameType(expr0, expr1) && (isInductive(expr0) || isInductive(expr1))
    })
  end contradictionIndeducible

  def withEqualities(equalities: Equalities): Option[Equalities] =
    withEqualities(equalities.pos) flatMap { _.withUnequalities(equalities.neg) }

  def withEqualities(pos: PatternConstraints): Option[Equalities] =
    withEqualities(pos.iterator map { _ -> _.asTerm })

  def withEqualities(pos: (Term, Term)): Option[Equalities] =
    withEqualities(Iterator(pos))

  def withEqualities(pos: IterableOnce[(Term, Term)]): Option[Equalities] =
    val iterator = pos.iterator
    if iterator.nonEmpty then
      Equalities(normalize(this.pos, iterator) filterNot { _ == _ }, this.neg)
        .propagatePos.propagateNeg.consolidateNeg
    else
      Some(this)

  def withoutEqualities(pos: (Term, Term)): Equalities =
    withoutEqualities(Iterator(pos))

  def withoutEqualities(pos: IterableOnce[(Term, Term)]): Equalities =
    Equalities(
      pos.iterator.foldLeft(this.pos) {
        case (pos, (expr0, expr1)) if expr1 < expr0 =>
          if pos.get(expr1) contains expr0 then pos - expr1 else pos
        case (pos, (expr0, expr1)) =>
          if pos.get(expr0) contains expr1 then pos - expr0 else pos
      },
      this.neg)

  def withUnequalities(neg: PatternConstraints): Option[Equalities] =
    withUnequalities(List(neg.iterator map { _ -> _.asTerm }))

  def withUnequalities(neg: IterableOnce[IterableOnce[(Term, Term)]]): Option[Equalities] =
    val iterator = neg.iterator
    if iterator.nonEmpty then
      Equalities(this.pos, (iterator map { neg => normalize(Map.empty, neg.iterator) }).toSet)
        .propagateNeg.consolidateNeg map { case Equalities(pos, neg) => Equalities(pos, this.neg ++ neg) }
    else
      Some(this)

  def posConstraints: PatternConstraints =
    val posConstraints = pos flatMap { (expr0, expr1) => expr1.asPattern map { expr0 -> _ } }
    PatternConstraints.make(posConstraints) getOrElse PatternConstraints.empty

  def negConstraints: Set[PatternConstraints] =
    neg flatMap { neg =>
      val negConstraints = neg flatMap { (expr0, expr1) => expr1.asPattern map { expr0 -> _ } }
      if negConstraints.sizeCompare(neg) == 0 then PatternConstraints.make(negConstraints) else None
    }

  extension (pattern: Pattern) private def asTerm: Term = pattern match
    case Match(ctor, args) => Data(pattern)(ctor, args map { _.asTerm })
    case Bind(ident) => Var(pattern)(ident)

  extension (expr: Term) private def asPattern: Option[Pattern] = expr match
    case Data(ctor, args) =>
      val patternArgs = args.foldRight[Option[List[Pattern]]](Some(List.empty)) { (arg, args) =>
        args flatMap { args => arg.asPattern map { _ :: args } }
      }
      patternArgs map { Match(expr)(ctor, _) }
    case _ =>
      None

  def posExpanded: Map[Term, Term] =
    val posReverse = pos.foldLeft(Map.empty[Term, Term]) { case (posReverse, (expr0, expr1)) =>
      if !(pos contains expr1) && !contains(expr0, expr1) && (posReverse.get(expr1) forall { _ < expr0 }) then
        posReverse + (expr1 -> expr0)
      else
        posReverse
    }

    val propagatedReverseExpanded = pos flatMap { (expr0, expr1) => 
      propagate(posReverse, expr0) -> expr1 match
        case expr -> _ if expr eq expr0 => None
        case expr0 -> expr1 if expr1 < expr0 => Some(expr1 -> expr0)
        case exprs => Some(exprs)
    }

    propagatedReverseExpanded ++ pos
  end posExpanded

  private def propagatePos: Equalities =
    val propagatedList = pos.toList map { (expr0, expr1) => 
      propagate(pos - expr0, expr0) -> propagate(pos, expr1) match
        case expr0 -> expr1 if expr1 < expr0 => expr1 -> expr0
        case exprs => exprs
    }

    def process(pos: List[(Term, Term)]): List[(Term, Term)] = pos match
      case Nil => Nil
      case (_: Data, _: Data) :: tail0 => process(tail0)
      case (expr0a, expr1a) :: tail0 =>
        def processTail(pos: List[(Term, Term)]): List[(Term, Term)] = pos match
          case Nil => Nil
          case (_: Data, _: Data) :: tail1 => processTail(tail1)
          case (expr0b, expr1b) :: tail1 =>
            val element0 = (expr0a, expr0b) match
              case (_, _: Data) | (_: Data, _) => Nil
              case element0 @ (expr0a, expr0b)
                  if equivalent(expr0a, expr0b) && equal(expr1a, expr1b) == Equality.Indeterminate =>
                element0 :: Nil
              case _ => Nil
            val element1 = (expr1a, expr1b) match
              case (_, _: Data) | (_: Data, _) => Nil
              case element1 @ (expr1a, expr1b)
                  if equivalent(expr1a, expr1b) && equal(expr0a, expr0b) == Equality.Indeterminate =>
                element1 :: Nil
              case _ => Nil
            element0 ++ element1 ++ processTail(tail1)
        process(tail0) ++ processTail(tail0)

    val equivalentList = process(propagatedList)

    def iterator = propagatedList.iterator ++ equivalentList.iterator

    val propagated = Map.from(iterator)

    if propagated != pos then
      val normalized = normalize(Map.empty, iterator) filterNot { _ == _ }
      if normalized != pos then
        if normalized != propagated then Equalities(normalized, neg).propagatePos else Equalities(normalized, neg)
      else
        this
    else
      this
  end propagatePos

  private def propagateNeg: Equalities =
    val propagatedList = neg map {
      _.toList map { propagate(pos, _) -> propagate(pos, _) match
        case expr0 -> expr1 if expr1 < expr0 => expr1 -> expr0
        case exprs => exprs
      }
    }

    val propagated = propagatedList map { _.toMap }

    if propagated != neg then
      val normalized = propagatedList map { neg => normalize(Map.empty, neg.iterator) }
      if normalized != neg then
        if normalized != propagated then Equalities(pos, normalized).propagateNeg else Equalities(pos, normalized)
      else
        this
    else
      this
  end propagateNeg

  private def propagate(pos: Map[Term, Term], expr: Term): Term = pos.getOrElse(expr, expr) match
    case term @ Abs(properties, ident, tpe, expr) =>
      Abs(term)(properties, ident, tpe, propagate(pos, expr))
    case term @ App(properties, expr, arg) =>
      App(term)(properties, propagate(pos, expr), propagate(pos, arg))
    case term @ TypeAbs(ident, expr) =>
      TypeAbs(term)(ident, propagate(pos, expr))
    case term @ TypeApp(expr, tpe) =>
      TypeApp(term)(propagate(pos, expr), tpe)
    case term @ Data(ctor, args) =>
      Data(term)(ctor, args map { propagate(pos, _) })
    case term @ Var(_) =>
      term
    case term @ Cases(scrutinee, cases) =>
      Cases(term)(propagate(pos, scrutinee), cases map { (pattern, expr) => pattern -> propagate(pos, expr) })

  private def consolidateNeg: Option[Equalities] =
    val checkNeg = Equalities(Map.empty, Set.empty)
    Option.when((pos forall { checkNeg.equal(_, _) != Equality.Unequal }) && (neg forall { _.nonEmpty })) {
      Equalities(pos, neg filter { _ forall { checkNeg.equal(_, _) != Equality.Unequal } })
    }

  private def normalize(pos: Map[Term, Term], equalities: Iterator[(Term, Term)]) =
    def destruct(expr0: Term, expr1: Term): List[(Term, Term)] = expr0 -> expr1 match
      case TypeApp(expr0, tpe0) -> TypeApp(expr1, tpe1) if equivalent(tpe0, tpe1) =>
        destruct(expr0, expr1)
      case Data(ctor0, args0) -> Data(ctor1, args1) if ctor0 == ctor1 && args0.sizeCompare(args1) == 0 =>
        args0 zip args1 flatMap destruct
      case _ if expr1 < expr0 =>
        List(expr1 -> expr0)
      case exprs =>
        List(exprs)

    def insert(equalities: Map[Term, Term], key: Term, value: Term): Map[Term, Term] =
      destruct(key, value).foldLeft(equalities) { case (equalities, (key, value)) =>
        if key != value then
          equalities.get(key) match
            case None =>
              equalities + (key -> value)
            case Some(expr) if expr != value =>
              val otherKey -> otherValue = if expr < value then expr -> value else value -> expr
              insert(equalities + (key -> otherValue), otherKey, otherValue)
            case _ =>
              equalities
        else
          equalities
      }

    equalities.foldLeft(pos) { case (equalities, (expr0, expr1)) =>
      if expr0 < expr1 then insert(equalities, expr0, expr1) else insert(equalities, expr1, expr0)
    }
end Equalities

object Equalities:
  def empty =
    Equalities(Map.empty, Set.empty)
  def make(pos: IterableOnce[(Term, Term)], neg: IterableOnce[IterableOnce[(Term, Term)]]) =
    empty.withEqualities(pos) flatMap { _.withUnequalities(neg) }
  def pos(pos: IterableOnce[(Term, Term)]) =
    empty.withEqualities(pos)
  def neg(neg: IterableOnce[IterableOnce[(Term, Term)]]) =
    empty.withUnequalities(neg)

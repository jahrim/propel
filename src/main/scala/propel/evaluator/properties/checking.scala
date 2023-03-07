package propel
package evaluator
package properties

import ast.*
import printing.*
import typer.*
import util.*

def check(
    expr: Term,
    discoverAlgebraicProperties: Boolean = true,
    assumedUncheckedConjectures: List[Normalization] = List.empty,
    assumedUncheckedNestedConjectures: List[Normalization] = List.empty,
    printDeductionDebugInfo: Boolean = false,
    printReductionDebugInfo: Boolean = false): Term =
  var debugInfoPrinted = false

  def indent(indentation: Int, string: String) =
    val indent = " " * indentation
    (string.linesIterator map { line => if line.nonEmpty then s"$indent$line" else line }).mkString(s"${System.lineSeparator}")

  val boolType = Sum(List(Constructor.True -> List.empty, Constructor.False -> List.empty))
  val relationType = Function(TypeVar(Symbol("T")), Function(TypeVar(Symbol("T")), boolType))
  val functionType = Function(TypeVar(Symbol("T")), Function(TypeVar(Symbol("T")), TypeVar(Symbol("T"))))
  val definition =
    Abs(Set.empty, Symbol(s"v${Naming.subscript(0)}"), TypeVar(Symbol("T")),
      Abs(Set.empty, Symbol(s"v${Naming.subscript(1)}"), TypeVar(Symbol("T")), Var(Symbol("e"))))

  def unknownPropertyError(property: Property) = Error(
    s"Property Deduction Error\n\nUnable to check ${property.show} property.")
  def illformedRelationTypeError(property: Property, tpe: Option[Type]) = Error(
    s"Property Deduction Error\n\nExpected relation type of shape ${relationType.show} to check ${property.show} property${
      if tpe.nonEmpty then s" but found ${tpe.get.show}." else "."
    }")
  def illformedFunctionTypeError(property: Property, tpe: Option[Type]) = Error(
    s"Property Deduction Error\n\nExpected function type of shape ${functionType.show} to check ${property.show} property${
      if tpe.nonEmpty then s" but found ${tpe.get.show}." else "."
    }")
  def illshapedDefinitionError(property: Property) = Error(
    s"Property Deduction Error\n\nExpected function definition of shape ${definition.show} to check ${property.show} property.")
  def propertyDeductionError(property: Property) = Error(
    s"Property Deduction Error\n\nUnable to prove ${property.show} property.")
  def propertyDisprovenError(property: Property) = Error(
    s"Property Deduction Error\n\nDisproved ${property.show} property.")

  def typedVar(ident: Symbol, tpe: Option[Type]) = tpe match
    case Some(tpe) =>
      val expr = Var(ident).withExtrinsicInfo(Typing.Specified(Right(tpe)))
      tpe.info(Abstraction) map { expr.withExtrinsicInfo(_) } getOrElse expr
    case _ =>
      Var(ident)

  def typedArgVar(ident: Symbol, tpe: Option[Type]) =
     typedVar(ident, tpe collect { case Function(arg, _) => arg })

  def typedBindings(pattern: Pattern): Map[Symbol, Term] = pattern match
    case Match(_, args) => (args flatMap typedBindings).toMap
    case Bind(ident) => Map(ident -> typedVar(ident, pattern.patternType))

  extension [A, B, C, D, E](list: List[(A, B, C, D, E)]) private def unzip5 =
    list.foldRight(List.empty[A], List.empty[B], List.empty[C], List.empty[D], List.empty[E]) {
      case ((elementA, elementB, elementC, elementD, elementE), (listA, listB, listC, listD, listE)) =>
       (elementA :: listA, elementB :: listB, elementC :: listC, elementD :: listD, elementE :: listE)
    }

  val typedExpr = expr.typedTerm

  val (abstractionProperties, abstractionResultTypes, abstractionDependencies, abstractionNames, namedVariables) =
    def abstractionInfos(term: Term, dependencies: List[Term | Symbol])
      : (Map[Abstraction, Properties], Map[Abstraction, Type], Map[Abstraction, List[Term | Symbol]], Map[Abstraction, String], Map[String, Var]) =
      term match
        case Abs(properties, ident, _, expr) =>
          term.info(Abstraction) flatMap { abstraction =>
            expr.termType map { tpe =>
              val (exprProperties, exprResultTypes, exprDependencies, exprNames, exprVars) =
                abstractionInfos(expr, dependencies :+ typedArgVar(ident, term.termType))
              (exprProperties + (abstraction -> properties),
               exprResultTypes + (abstraction -> tpe),
               exprDependencies + (abstraction -> dependencies),
               exprNames,
               exprVars)
            }
          } getOrElse abstractionInfos(expr, dependencies :+ typedArgVar(ident, term.termType))
        case App(_, expr, arg) =>
          val (exprProperties, exprResultTypes, exprDependencies, exprNames, exprVars) = abstractionInfos(expr, dependencies)
          val (argProperties, argResultTypes, argDependencies, argNames, argVars) = abstractionInfos(arg, dependencies)
          (exprProperties ++ argProperties, exprResultTypes ++ argResultTypes, exprDependencies ++ argDependencies, exprNames ++ argNames, exprVars ++ argVars)
        case TypeAbs(ident, expr) =>
          term.info(Abstraction) flatMap { abstraction =>
            expr.termType map { tpe =>
              val (exprProperties, exprResultTypes, exprDependencies, exprNames, exprVars) =
                abstractionInfos(expr, dependencies :+ typedArgVar(ident, term.termType))
              (exprProperties,
               exprResultTypes + (abstraction -> tpe),
               exprDependencies + (abstraction -> dependencies),
               exprNames,
               exprVars)
            }
          } getOrElse abstractionInfos(expr, dependencies :+ ident)
        case TypeApp(expr, _) =>
          abstractionInfos(expr, dependencies)
        case Data(_, args) =>
          val (argsProperties, argsResultTypes, argsDependencies, argsNames, argsVars) = (args map { abstractionInfos(_, dependencies) }).unzip5
          (argsProperties.flatten.toMap, argsResultTypes.flatten.toMap, argsDependencies.flatten.toMap, argsNames.flatten.toMap, argsVars.flatten.toMap)
        case variable @ Var(ident) =>
          (Map.empty, Map.empty, Map.empty, (term.info(Abstraction) map { _ -> ident.name }).toMap, Map(ident.name -> variable))
        case Cases(scrutinee, cases) =>
          val (scrutineeProperties, scrutineeResultTypes, scrutineeDependencies, scrutineeNames, scrutineeVars) =
            abstractionInfos(scrutinee, dependencies)
          val (casesProperties, casesResultTypes, casesDependencies, casesNames, casesVars) =
            (cases map { (_, expr) => abstractionInfos(expr, dependencies) }).unzip5
          (scrutineeProperties ++ casesProperties.flatten,
           scrutineeResultTypes ++ casesResultTypes.flatten,
           scrutineeDependencies ++ casesDependencies.flatten,
           scrutineeNames ++ casesNames.flatten,
           scrutineeVars ++ casesVars.flatten)

    abstractionInfos(typedExpr, List.empty)

  def typeContradiction(abstraction: Term, expr: Term) =
    abstraction.info(Abstraction) exists { abstraction =>
      abstractionResultTypes.get(abstraction) exists { tpe =>
        expr.termType exists { !conforms(_, tpe) }
      }
    }

  def deriveTypeContradiction(equalities: Equalities): PartialFunction[(Term, Term), List[Equalities]] =
    case (App(_, abstraction, _), expr) if typeContradiction(abstraction, expr) =>
      Equalities.pos(List(Data(Constructor.False, List.empty) -> Data(Constructor.True, List.empty))).toList
    case (expr, App(_, abstraction, _)) if typeContradiction(abstraction, expr) =>
      Equalities.pos(List(Data(Constructor.False, List.empty) -> Data(Constructor.True, List.empty))).toList

  var additionalProperties = Map.empty[Abstraction, Properties]

  def addPropertiesToCalls(term: Term, abstractionProps: Map[Abstraction, Properties], identProps: Map[Symbol, Properties]): Term = term match
    case Abs(props, ident, tpe, expr) =>
      Abs(term)(desugar(props), ident, tpe, addPropertiesToCalls(expr, abstractionProps, identProps))
    case App(props, expr, arg) =>
      val propsAbstractions = expr.info(Abstraction) flatMap abstractionProps.get getOrElse Set.empty
      val propsIdents = expr match
        case Var(ident) => identProps.get(ident) getOrElse Set.empty
        case _ => Set.empty
      App(term)(desugar(props ++ propsAbstractions ++ propsIdents), addPropertiesToCalls(expr, abstractionProps, identProps), addPropertiesToCalls(arg, abstractionProps, identProps))
    case TypeAbs(ident, expr) =>
      TypeAbs(term)(ident, addPropertiesToCalls(expr, abstractionProps, identProps))
    case TypeApp(expr, tpe) =>
      TypeApp(term)(addPropertiesToCalls(expr, abstractionProps, identProps), tpe)
    case Data(ctor, args) =>
      Data(term)(ctor, args map { addPropertiesToCalls(_, abstractionProps, identProps) })
    case Var(_) =>
      term
    case Cases(scrutinee, cases) =>
      Cases(term)(
        addPropertiesToCalls(scrutinee, abstractionProps, identProps),
        cases map { (pattern, expr) => pattern -> addPropertiesToCalls(expr, abstractionProps, identProps) })

  var collectedNormalizations = List.empty[(Abstraction => Option[Term]) => Option[Equalities => PartialFunction[Term, Term]]]

  def addCollectedNormalizations(env: Map[Symbol, Term], abstraction: Option[Abstraction], normalizations: List[Normalization]) =
    abstraction foreach { abstraction =>
      collectedNormalizations ++= normalizations map { property => exprs =>
        exprs(abstraction) map { expr =>
          val freeExpr = (property.free flatMap { ident =>
            env.get(ident) flatMap { _.info(Abstraction) flatMap exprs map { ident -> _ } }
          }).toMap

          property.checking(
            expr,
            _ forall { _.info(Abstraction) contains abstraction },
            _ forall { (ident, exprs) => env.get(ident) exists { expr =>
              val abstraction = expr.info(Abstraction)
              abstraction exists { abstraction => exprs forall { _.info(Abstraction) contains abstraction } }
            } },
            freeExpr,
            ensureDecreasingArgsForBinaryAbstraction = false).normalize(PropertyChecking.withNonDecreasing)
        }
      }
    }

  val assumedUncheckedConjecturesEnvironment =
    (assumedUncheckedConjectures flatMap { normalization =>
      namedVariables.get(normalization.abstraction.name) flatMap { variable =>
        val abstraction = variable.info(Abstraction)
        if abstraction.isDefined then
          addCollectedNormalizations(Map.empty, abstraction, List(normalization))
          Some(variable.ident -> variable)
        else
          None
      }
    }).toMap

  def exprArgumentPrefixes(expr: Term): List[(List[(Symbol, Type)], Term)] = expr match
    case Abs(_, ident, tpe, expr) =>
      (List(ident -> tpe), expr) :: (exprArgumentPrefixes(expr) map { (identTypes, expr) =>
        (ident -> tpe :: identTypes, expr)
      })
    case _ =>
      List.empty

  def abstractionAccess(env: Map[Symbol, Term], dependencies: List[Term | Symbol]) =
    def nested(expr: Term): Map[Abstraction, (Term, Abstraction, Boolean)] =
      expr.termType match
        case Some(Function(Sum(List(ctor -> List())), _)) =>
          val abstraction = expr.info(Abstraction)
          val properties = abstraction flatMap abstractionProperties.get getOrElse Set.empty
          val result = App(properties, expr, Data(ctor, List.empty)).typedTerm
          nested(result) ++ dependent(expr, abstraction)
        case Some(Sum(List(ctor -> args))) =>
          val size = args.size
          (args.zipWithIndex flatMap { (arg, index) =>
            val name = arg.info(Abstraction) map { abstractionNames.get(_) getOrElse "∘" } getOrElse "∙"
            val args = List.tabulate(size) { i => Bind(Symbol(if i == index then name else "_")) }
            val result = Cases(expr, List(Match(ctor, args) -> Var(Symbol(name)))).typedTerm
            nested(result)
          }).toMap
        case _ =>
          dependent(expr, expr.info(Abstraction))

    def dependent(expr: Term, base: Option[Abstraction]): Map[Abstraction, (Term, Abstraction, Boolean)] =
      def dependent(
          expr: Term,
          dependencies: List[Term | Symbol],
          base: Abstraction,
          advanceBase: Boolean,
          fundamental: Boolean): Map[Abstraction, (Term, Abstraction, Boolean)] =
        dependencies match
          case arg :: dependencies =>
            val abstraction = expr.info(Abstraction)
            val properties = abstraction flatMap abstractionProperties.get getOrElse Set.empty
            val ((result, tpe), advance) = arg match
              case arg: Term => App(properties, expr, arg).typed -> false
              case arg: Symbol => TypeApp(expr, TypeVar(arg)).withExtrinsicInfo(Typing.Specified(Left(Set(arg)))).typed -> advanceBase
            if tpe exists { _.info(Abstraction) exists { abstractionResultTypes contains _ } } then
              dependent(result, dependencies, if advance then result.info(Abstraction) getOrElse base else base, advance, fundamental = false) ++
              (abstraction map { _ -> (expr, base, fundamental) })
            else
              (abstraction map { _ -> (expr, base, fundamental) }).toMap
          case _ =>
            (expr.info(Abstraction) map { _ -> (expr, base, fundamental) }).toMap

      (base
        map { base =>
          (abstractionDependencies.get(base)
            collect { case baseDependencies if dependencies startsWith baseDependencies =>
              dependent(expr, dependencies.drop(baseDependencies.size), base, advanceBase = true, fundamental = true)
            }
            getOrElse Map(base -> (expr, base, true)))
        }
        getOrElse Map.empty)

    env flatMap { (_, expr) => nested(expr) }

  def check(term: Term, env: Map[Symbol, Term], dependencies: List[Term | Symbol]): Term = term match
    case Abs(properties, ident0, tpe0, expr0 @ Abs(_, ident1, tpe1, expr1)) =>
      val abstractions = abstractionAccess(env, dependencies)

      val (abstraction, call) = (term.info(Abstraction)
        flatMap { abstractions.get(_) map { (expr, base, _) => Some(base) -> Some(expr) } }
        getOrElse (None, None))

      val resultType = term.termType collect { case Function(_, Function(_, result)) => result }

      val names = env.keys map { _.name }

      val name = term.info(Abstraction) flatMap abstractionNames.get

      val fundamentalAbstractions = (abstractions collect { case abstraction -> (_, _, true) => abstraction }).toSet

      val collectedNormalize = collectedNormalizations flatMap { _(abstractions.get(_) map { (expr, _, _) => expr }) }

      if printReductionDebugInfo || printDeductionDebugInfo then
        if debugInfoPrinted then
          println()
        else
          debugInfoPrinted = true
        println(s"Checking properties for definition${ name map { name => s" ($name)" } getOrElse "" }:")
        println()
        println(indent(2, term.show))

      val result = Symbolic.eval(UniqueNames.convert(expr1, names))

      val (facts, conjectures) =
        val updatedTerm = addPropertiesToCalls(term, additionalProperties, Map.empty)

        val (basicFacts, generalizedConjectures) = (exprArgumentPrefixes(updatedTerm) map { (identTypes, expr) =>
          val (idents, _) = identTypes.unzip

          val evaluationResult =
            if expr ne expr1 then
              Symbolic.eval(UniqueNames.convert(expr, names))
            else
              result

          val generalizedConjectures =
            if evaluationResult.wrapped.reductions.sizeIs > 1 then
              Conjecture.generalizedConjectures(
                abstractionProperties.get,
                env.get(_) exists { _.info(Abstraction) exists { abstractions contains _ } },
                updatedTerm,
                call,
                identTypes,
                evaluationResult)
            else
              List.empty

          val basicFacts = Conjecture.basicFacts(
            abstractionProperties.get,
            env.get(_) exists { _.info(Abstraction) exists { abstractions contains _ } },
            updatedTerm,
            call filter { _.info(Abstraction) exists { abstraction contains _ } },
            idents,
            evaluationResult)

          basicFacts -> generalizedConjectures
        }).unzip

        val facts = basicFacts.flatten

        (facts,
         generalizedConjectures.flatten ++
         Conjecture.distributivityConjectures(properties, updatedTerm))

      val normalizeFacts =
        facts map { fact =>
          val (_, normalization) = fact.checking(
            call,
            _ forall { _.info(Abstraction) contains abstraction.get },
            _ forall { (ident, exprs) => env.get(ident) exists { expr =>
              val abstraction = expr.info(Abstraction)
              abstraction exists { abstraction => exprs forall { _.info(Abstraction) contains abstraction } }
            } },
            (fact.free flatMap { ident => env.get(ident) map { ident -> _ } }).toMap,
            ensureDecreasingArgsForBinaryAbstraction = false)
          normalization map { _.normalize(PropertyChecking.withNonDecreasing) }
        }

      if printDeductionDebugInfo then
        if call.isEmpty then
          println()
          println(indent(2, "No known recursion scheme detected."))

        if facts.nonEmpty then
          println()
          println(indent(2, "Basic facts:"))
          println()
          facts map { fact => println(indent(4, fact.show)) }

        if conjectures.nonEmpty then
          println()
          println(indent(2, "Generalized conjectures:"))
          println()
          conjectures map { conjecture => println(indent(4, conjecture.show)) }

      def proveConjectures(
          conjectures: List[Normalization],
          provenConjectures: List[Normalization] = List.empty,
          normalizeConjectures: List[Option[Equalities => PartialFunction[Term, Term]]] = List.empty)
      : (List[Normalization], List[Option[Equalities => PartialFunction[Term, Term]]]) =

        val init = (List.empty[Normalization], List.empty[(Normalization, Option[Equalities => PartialFunction[Term, Term]])])

        val (remaining, additional) = conjectures.foldLeft(init) { case (processed @ (remaining, proven), conjecture) =>
          if !Normalization.specializationForSameAbstraction(conjecture, proven map { case proven -> _ => proven }) then
            def makeNormalization(conjecture: Normalization, ensureDecreasingArgsForBinaryAbstraction: Boolean) =
              conjecture.checking(
                call,
                _ forall { _.info(Abstraction) contains abstraction.get },
                _ forall { (ident, exprs) => env.get(ident) exists { expr =>
                  val abstraction = expr.info(Abstraction)
                  abstraction exists { abstraction => exprs forall { _.info(Abstraction) contains abstraction } }
                } },
                (conjecture.free flatMap { ident => env.get(ident) map { ident -> _ } }).toMap,
                ensureDecreasingArgsForBinaryAbstraction)

            def makeNormalizations(conjecture: Normalization) =
              val (checking, normalization) = makeNormalization(conjecture, ensureDecreasingArgsForBinaryAbstraction = false)
              val (_, checkingNormalization) = makeNormalization(conjecture, ensureDecreasingArgsForBinaryAbstraction = true)
              (checking, normalization, checkingNormalization)

            val (checking, normalization, checkingNormalization) = makeNormalizations(conjecture)

            val normalizeConjecture = checkingNormalization map { _.normalize(PropertyChecking.withNonDecreasing) }

            val (reverseChecking, reverseNormalization, checkingReverseNormalization) = (conjecture.reverse
              map { conjecture =>
                val (checking, normalization, checkingNormalization) = makeNormalizations(conjecture)
                (Some(checking), normalization, checkingNormalization)
              }
              getOrElse (None, None, None))

            val reverseNormalizeConjecture = checkingReverseNormalization map { _.normalize(PropertyChecking.withNonDecreasing) }

            def checkConjecture(checking: PropertyChecking) =
              val (expr, equalities) = checking.prepare(ident0, ident1, addPropertiesToCalls(expr1, additionalProperties, Map.empty))
              val converted = UniqueNames.convert(expr, names)

              if printReductionDebugInfo then
                println()
                println(indent(2, s"Checking conjecture: ${conjecture.show}"))
                println()
                println(indent(4, converted.wrapped.show))

              val config = Symbolic.Configuration(
                evaluator.properties.normalize(
                  normalizeFacts.flatten ++
                  normalizeConjecture.toList ++
                  reverseNormalizeConjecture.toList ++
                  normalizeConjectures.flatten ++
                  collectedNormalize ++
                  (normalizing map { _(PropertyChecking.withNonDecreasing) }),
                  abstraction.fold((_: Var) => false) { abstraction => _.info(Abstraction) contains abstraction },
                  fundamentalAbstractions.contains, _, _),
                evaluator.properties.select(selecting, _, _),
                evaluator.properties.derive(
                  derivingCompound,
                  deriveTypeContradiction :: derivingSimple, _),
                checking.control)

              val result = Symbolic.eval(converted, equalities, config)

              val check = checking.check(List(ident0, ident1), result.wrapped)
              val proved = check exists { _.reductions.isEmpty }
              val disproved = check.isLeft

              if printReductionDebugInfo then
                println()
                println(indent(2, "Evaluation result for conjecture check:"))
                if !proved then
                  println(indent(2, "(some cases may not be fully reduced)"))
                println()
                println(indent(4, result.wrapped.show))
                if !proved then
                  println()
                  println(indent(2, "Offending cases for conjecture check:"))
                  println(indent(2, "(some cases may not be fully reduced)"))
                  println()
                  println(indent(4, check.merge.show))
                println()
                if proved then
                  println(indent(4, "✔ Conjecture proven".toUpperCase.nn))
                else if disproved then
                  println(indent(4, "  Conjecture disproved".toUpperCase.nn))
                else
                  println(indent(4, "✘ Conjecture could not be proved".toUpperCase.nn))

              (proved, disproved)
            end checkConjecture

            val (proved, disproved) =
              if !Normalization.specializationForSameAbstraction(conjecture, facts) then
                val result @ (proved, disproved) = checkConjecture(checking)
                if !proved && !disproved && reverseChecking.isDefined then checkConjecture(reverseChecking.get)
                else result
              else
                (true, false)

            if proved then
              conjecture.reverse match
                case Some(reverseConjecture) =>
                  (remaining,
                    (conjecture -> (normalization map { _.normalize(PropertyChecking.withNonDecreasing) })) ::
                    (reverseConjecture -> (reverseNormalization map { _.normalize(PropertyChecking.withNonDecreasing) })) :: proven)
                case _ =>
                  (remaining,
                    (conjecture -> (normalization map { _.normalize(PropertyChecking.withNonDecreasing) })) :: proven)
            else if disproved then
              (remaining, proven)
            else
              (conjecture :: remaining, proven)
          else
            processed
        }

        val (proven, normalize) = additional.unzip

        if remaining.isEmpty || additional.isEmpty then
          (provenConjectures ++ proven, normalizeConjectures ++ normalize)
        else
          proveConjectures(remaining, provenConjectures ++ proven, normalizeConjectures ++ normalize)
      end proveConjectures

      val (uncheckedConjectures, uncheckedNormalizeConjecture) =
        (assumedUncheckedNestedConjectures collect { case conjecture if name contains conjecture.abstraction.name =>
          val (_, normalization) = conjecture.checking(
            call,
            _ forall { _.info(Abstraction) contains abstraction.get },
            _ forall { (ident, exprs) => env.get(ident) exists { expr =>
              val abstraction = expr.info(Abstraction)
              abstraction exists { abstraction => exprs forall { _.info(Abstraction) contains abstraction } }
            } },
            (conjecture.free flatMap { ident => env.get(ident) map { ident -> _ } }).toMap,
            ensureDecreasingArgsForBinaryAbstraction = false)
          conjecture -> (normalization map { _.normalize(PropertyChecking.withNonDecreasing) })
        }).unzip

      val (provenConjectures, normalizeConjectures) = proveConjectures(
        conjectures sortWith { !Normalization.specializationForSameAbstraction(_, _) },
        uncheckedConjectures,
        uncheckedNormalizeConjecture)

      val (provenProperties, normalize) =
        type Properties = List[(Normalization, Option[Equalities => PartialFunction[Term, Term]])]

        def distinct(properties: Properties): Properties = properties match
          case Nil => Nil
          case (head @ (propertyHead, _)) :: tail =>
            def distinctTail(properties: Properties): Properties = properties match
              case Nil => Nil
              case (head @ (property, _)) :: tail =>
                if Normalization.equivalentForSameAbstraction(property, propertyHead) then
                  distinctTail(tail)
                else
                  head :: distinctTail(tail)
            head :: distinct(distinctTail(tail))

        val distinctPropertiesNormalize = distinct(provenConjectures zip normalizeConjectures)
        val (distinctProperties, _) = distinctPropertiesNormalize.unzip
        (distinctPropertiesNormalize
          filterNot { (property, _) =>
            Normalization.specializationForSameAbstraction(property, distinctProperties filterNot { _ eq property })
          }
          sortBy { case Normalization(pattern, result, _, _, _) -> _ =>
            (pattern, result)
          }).unzip

      if printDeductionDebugInfo && (conjectures.nonEmpty || uncheckedConjectures.nonEmpty) && provenProperties.nonEmpty then
        println()
        println(indent(2, "Proven properties:"))
        println()
        provenProperties foreach { property => println(indent(4, property.show)) }

      val hasRelationPropertyShape = equivalent(tpe0, tpe1) && (resultType forall { conforms(_, boolType) })
      val hasFunctionPropertyShape = equivalent(tpe0, tpe1) && (resultType forall { conforms(_, tpe0) })

      val optimisticProperties =
        if hasRelationPropertyShape then
          List(Reflexive, Irreflexive, Antisymmetric, Symmetric, Connected, Transitive)
        else if hasFunctionPropertyShape then
          List(Commutative, Selection, Idempotent, Associative)
        else
          List.empty

      val desugaredProperties = desugar(properties)

      val checkingProperties =
        if discoverAlgebraicProperties then
          optimisticProperties ++ (desugaredProperties -- optimisticProperties)
        else
          (optimisticProperties filter { desugaredProperties contains _ }) ++ (desugaredProperties -- optimisticProperties)

      var checkedProperties = Set.empty[Property]

      val error = checkingProperties collectFirstDefined { property =>
        propertiesChecking get property match
          case None =>
            Some(unknownPropertyError(property))
          case Some(checking) =>
            if checking.propertyType == PropertyType.Relation && !hasRelationPropertyShape then
              Some(illformedRelationTypeError(property, term.termType))
            else if checking.propertyType == PropertyType.Function && !hasFunctionPropertyShape then
              Some(illformedFunctionTypeError(property, term.termType))
            else
              val checkingProperties = term.info(Abstraction).fold(additionalProperties) { abstraction =>
                additionalProperties.updatedWith(abstraction) { properties => Some(properties.toSet.flatten + property) }
              }

              val (expr, equalities) = checking.prepare(ident0, ident1, addPropertiesToCalls(expr1, checkingProperties, Map.empty))
              val converted = UniqueNames.convert(expr, names)

              if printReductionDebugInfo then
                println()
                println(indent(2, s"Checking ${property.show} property:"))
                println()
                println(indent(4, converted.wrapped.show))

              val addingProperties = desugaredProperties ++ checkedProperties + property

              def addPropertiesAndEnsureDecreasingArgs(
                normalizing: ((Property, Term) => Boolean) => Equalities => PartialFunction[Term, Term])
              : Equalities => PartialFunction[Term, Term] =
                term.info(Abstraction).fold(normalizing(PropertyChecking.withNonDecreasing)) { abstraction => equalities =>
                  val normalize = normalizing((prop, expr) => (expr.info(Abstraction) contains abstraction) && prop == property)(equalities)

                  inline def addProperties(app: App) =
                    if app.expr.info(Abstraction) contains abstraction then
                      App(app)(app.properties ++ addingProperties, app.expr, app.arg)
                    else
                      app

                  normalize compose {
                    case term @ App(props0, expr0: App, app1 @ App(props1, expr1: App, arg2)) =>
                      App(term)(props0, addProperties(expr0), App(app1)(props1, addProperties(expr1), arg2))
                    case term @ App(props0, app0 @ App(properties0, expr0, app1 @ App(props1, expr1: App, arg1)), arg2) =>
                      App(term)(props0, addProperties(App(app0)(properties0, expr0, App(app1)(props1, addProperties(expr1), arg1))), arg2)
                    case term @ App(props, expr: App, arg) =>
                      App(term)(props, addProperties(expr), arg)
                  } orElse normalize
                }

              val config = Symbolic.Configuration(
                evaluator.properties.normalize(
                  normalize.flatten ++ collectedNormalize ++ (normalizing map addPropertiesAndEnsureDecreasingArgs),
                  abstraction.fold((_: Var) => false) { abstraction => _.info(Abstraction) contains abstraction },
                  fundamentalAbstractions.contains, _, _),
                evaluator.properties.select(selecting, _, _),
                evaluator.properties.derive(
                  derivingCompound,
                  deriveTypeContradiction :: derivingSimple, _),
                checking.control)

              val result = Symbolic.eval(converted, equalities, config)

              val check = checking.check(List(ident0, ident1), result.wrapped)
              val proved = check exists { _.reductions.isEmpty }
              val disproved = check.isLeft

              if printReductionDebugInfo then
                println()
                println(indent(2, s"Evaluation result for ${property.show} property check:"))
                if !proved then
                  println(indent(2, "(some cases may not be fully reduced)"))
                println()
                println(indent(4, result.wrapped.show))
                if !proved then
                  println()
                  println(indent(2, s"Offending cases for ${property.show} property check:"))
                  println(indent(2, "(some cases may not be fully reduced)"))
                  println()
                  println(indent(4, check.merge.show))

              if printReductionDebugInfo || printDeductionDebugInfo then
                println()
                if proved then
                  println(indent(4, s"✔ ${property.show} property proven".toUpperCase.nn))
                else if disproved then
                  println(indent(4, s"✘ ${property.show} property disproved".toUpperCase.nn))
                else
                  println(indent(4, s"✘ ${property.show} property could not be proved".toUpperCase.nn))

              if disproved then
                Option.when(desugaredProperties contains property)(propertyDisprovenError(property))
              else if !proved then
                Option.when(desugaredProperties contains property)(propertyDeductionError(property))
              else
                checkedProperties += property
                additionalProperties = checkingProperties
                None
      }

      val checkedNormalizations =
        provenProperties map { case Normalization(pattern, result, abstraction, variables, reversible) =>
          Normalization(
            addPropertiesToCalls(pattern, Map.empty, Map(abstraction -> (desugaredProperties ++ checkedProperties))),
            addPropertiesToCalls(result, Map.empty, Map(abstraction -> (desugaredProperties ++ checkedProperties))),
            abstraction,
            variables,
            reversible)
        }

      addCollectedNormalizations(env, abstraction, checkedNormalizations)

      error match
        case Some(error) =>
          term.withExtrinsicInfo(error)
        case _ =>
          val arg = typedArgVar(ident0, term.termType)
          val expr = Abs(term)(properties, ident0, tpe0, check(expr0, env + (ident0 -> arg), dependencies :+ arg)).typedTerm
          if checkedProperties.nonEmpty || checkedNormalizations.nonEmpty then
            val derived = Derived(checkedProperties, checkedNormalizations)
            expr.withExtrinsicInfo(derived)
          else
            expr

    case Abs(properties, ident, tpe, expr) =>
      if properties.nonEmpty then
        term.withExtrinsicInfo(illshapedDefinitionError(properties.head))
      else
        val abstractions = abstractionAccess(env, dependencies)

        val (abstraction, call) = (term.info(Abstraction)
          flatMap { abstractions.get(_) map { (expr, base, _) => Some(base) -> Some(expr) } }
          getOrElse (None, None))

        val names = env.keys map { _.name }

        val name = abstraction flatMap abstractionNames.get

        if printReductionDebugInfo || printDeductionDebugInfo then
          if debugInfoPrinted then
            println()
          else
            debugInfoPrinted = true
          println(s"Checking properties for definition${ name map { name => s" ($name)" } getOrElse "" }:")
          println()
          println(indent(2, term.show))

        val facts = exprArgumentPrefixes(term) flatMap { (identTypes, expr) =>
          val (idents, _) = identTypes.unzip
          Conjecture.basicFacts(
            abstractionProperties.get,
            env.get(_) exists { _.info(Abstraction) exists { abstractions contains _ } },
            term,
            call filter { _.info(Abstraction) exists { abstraction contains _ } },
            idents,
            Symbolic.eval(UniqueNames.convert(expr, names)))
        }

        if printDeductionDebugInfo && facts.nonEmpty then
          println()
          println(indent(2, "Basic facts:"))
          println()
          facts map { fact => println(indent(4, fact.show)) }

        val uncheckedConjectures = assumedUncheckedNestedConjectures collect { case conjecture if name contains conjecture.abstraction.name =>
          conjecture
        }

        if printDeductionDebugInfo && uncheckedConjectures.nonEmpty then
          println()
          println(indent(2, "Generalized conjectures:"))
          println()
          uncheckedConjectures map { conjecture => println(indent(4, conjecture.show)) }
          println()
          println(indent(2, "Proven properties:"))
          println()
          uncheckedConjectures foreach { property => println(indent(4, property.show)) }

        addCollectedNormalizations(env, abstraction, facts ++ uncheckedConjectures)

        val arg = typedArgVar(ident, term.termType)
        Abs(term)(properties, ident, tpe, check(expr, env + (ident -> arg), dependencies :+ arg)).typedTerm

    case App(properties, expr, arg) =>
      val checkedArg = check(arg, env, dependencies)
      val checkedExpr = check(expr, env, dependencies)
      App(term)(properties, checkedExpr, checkedArg).typedTerm

    case TypeAbs(ident, expr) =>
      TypeAbs(term)(ident, check(expr, env, dependencies :+ ident)).typedTerm

    case TypeApp(expr, tpe) =>
      TypeApp(term)(check(expr, env, dependencies), tpe).typedTerm

    case Data(ctor, args) =>
      Data(term)(ctor, args map { check(_, env, dependencies) }).typedTerm

    case Var(_) =>
      term

    case Cases(scrutinee, cases) =>
      Cases(term)(
        check(scrutinee, env, dependencies),
        cases map { (pattern, expr) => pattern -> check(expr, env ++ typedBindings(pattern), dependencies) }).typedTerm

  if typedExpr.termType.isDefined then
    check(typedExpr, assumedUncheckedConjecturesEnvironment, List.empty)
  else
    typedExpr
end check

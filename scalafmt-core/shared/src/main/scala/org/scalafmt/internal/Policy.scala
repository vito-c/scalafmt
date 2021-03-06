package org.scalafmt.internal

import scala.meta.tokens.Token

/**
  * The decision made by [[Router]].
  *
  * Used by [[Policy]] to enforce non-local formatting.
  */
abstract class Policy {

  /** applied to every decision until expire */
  def f: Policy.Pf

  def exists(pred: Policy.Clause => Boolean): Boolean
  def filter(pred: Policy.Clause => Boolean): Policy
  def unexpired(ft: FormatToken): Policy
  def noDequeue: Boolean

  def &(other: Policy): Policy =
    if (other.isEmpty) this else new Policy.AndThen(this, other)
  def |(other: Policy): Policy =
    if (other.isEmpty) this else new Policy.OrElse(this, other)

  @inline
  final def unexpiredOpt(ft: FormatToken): Option[Policy] =
    Some(unexpired(ft)).filter(_.nonEmpty)

  @inline
  final def &(other: Option[Policy]): Policy = other.fold(this)(&)
  @inline
  final def |(other: Option[Policy]): Policy = other.fold(this)(|)

  @inline
  final def isEmpty: Boolean = this eq Policy.NoPolicy
  @inline
  final def nonEmpty: Boolean = this ne Policy.NoPolicy

}

object Policy {

  type Pf = PartialFunction[Decision, Seq[Split]]

  object NoPolicy extends Policy {
    override def toString: String = "NoPolicy"
    override def f: Pf = PartialFunction.empty
    override def |(other: Policy): Policy = other
    override def &(other: Policy): Policy = other

    override def unexpired(ft: FormatToken): Policy = this
    override def filter(pred: Clause => Boolean): Policy = this
    override def exists(pred: Clause => Boolean): Boolean = false
    override def noDequeue: Boolean = false
  }

  def apply(
      endPolicy: End.WithPos,
      noDequeue: Boolean = false
  )(f: Pf)(implicit line: sourcecode.Line): Policy =
    new ClauseImpl(f, endPolicy, noDequeue)

  def after(
      token: Token,
      noDequeue: Boolean = false
  )(f: Pf)(implicit line: sourcecode.Line): Policy =
    apply(End.After(token), noDequeue)(f)

  def before(
      token: Token,
      noDequeue: Boolean = false
  )(f: Pf)(implicit line: sourcecode.Line): Policy =
    apply(End.Before(token), noDequeue)(f)

  def on(
      token: Token,
      noDequeue: Boolean = false
  )(f: Pf)(implicit line: sourcecode.Line): Policy =
    apply(End.On(token), noDequeue)(f)

  abstract class Clause(implicit val line: sourcecode.Line) extends Policy {
    val f: Policy.Pf
    val endPolicy: End.WithPos

    override def toString = {
      val noDeqPrefix = if (noDequeue) "!" else ""
      s"${line.value}$endPolicy${noDeqPrefix}d"
    }

    override def unexpired(ft: FormatToken): Policy =
      if (endPolicy.notExpiredBy(ft)) this else NoPolicy

    override def filter(pred: Clause => Boolean): Policy =
      if (pred(this)) this else NoPolicy

    override def exists(pred: Clause => Boolean): Boolean = pred(this)
  }

  private class ClauseImpl(
      val f: Policy.Pf,
      val endPolicy: End.WithPos,
      val noDequeue: Boolean
  )(implicit line: sourcecode.Line)
      extends Clause

  private class OrElse(p1: Policy, p2: Policy) extends Policy {
    override lazy val f: Pf = p1.f.orElse(p2.f)

    override def unexpired(ft: FormatToken): Policy =
      p1.unexpired(ft) | p2.unexpired(ft)

    override def filter(pred: Clause => Boolean): Policy =
      p1.filter(pred) | p2.filter(pred)

    override def exists(pred: Clause => Boolean): Boolean =
      p1.exists(pred) || p2.exists(pred)

    override def noDequeue: Boolean =
      p1.noDequeue || p2.noDequeue

    override def toString: String = s"($p1 | $p2)"
  }

  private class AndThen(p1: Policy, p2: Policy) extends Policy {
    override lazy val f: Pf = {
      case x =>
        p2.f.applyOrElse(
          p1.f.andThen(x.withSplits _).applyOrElse(x, identity[Decision]),
          (y: Decision) => y.splits
        )
    }

    override def unexpired(ft: FormatToken): Policy =
      p1.unexpired(ft) & p2.unexpired(ft)

    override def filter(pred: Clause => Boolean): Policy =
      p1.filter(pred) & p2.filter(pred)

    override def exists(pred: Clause => Boolean): Boolean =
      p1.exists(pred) || p2.exists(pred)

    override def noDequeue: Boolean =
      p1.noDequeue || p2.noDequeue

    override def toString: String = s"($p1 & $p2)"
  }

  object Proxy {
    def apply(
        policy: Policy,
        end: End.WithPos
    )(factory: Policy => Pf)(implicit line: sourcecode.Line): Policy =
      if (policy.isEmpty) NoPolicy
      else new Proxy(policy, factory, end)
  }

  private class Proxy(
      policy: Policy,
      factory: Policy => Policy.Pf,
      override val endPolicy: End.WithPos
  )(implicit line: sourcecode.Line)
      extends Policy.Clause {
    override val f: Pf = factory(policy)

    override def exists(pred: Clause => Boolean): Boolean =
      pred(this) || policy.exists(pred)

    override def filter(pred: Clause => Boolean): Policy =
      if (!pred(this)) NoPolicy
      else Proxy(policy.filter(pred), endPolicy)(factory)

    override def unexpired(ft: FormatToken): Policy =
      if (!endPolicy.notExpiredBy(ft)) NoPolicy
      else Proxy(policy.unexpired(ft), endPolicy)(factory)

    override def noDequeue: Boolean = policy.noDequeue

    override def toString: String = s"*($policy)$endPolicy"
  }

  sealed trait End extends (Token => End.WithPos) {
    def apply(endPos: Int): End.WithPos
    final def apply(token: Token): End.WithPos = apply(token.end)
  }
  object End {
    sealed trait WithPos {
      def notExpiredBy(ft: FormatToken): Boolean
    }
    case object After extends End {
      def apply(endPos: Int): WithPos =
        new End.WithPos {
          def notExpiredBy(ft: FormatToken): Boolean = ft.left.end <= endPos
          override def toString: String = s">$endPos"
        }
    }
    case object Before extends End {
      def apply(endPos: Int): WithPos =
        new End.WithPos {
          def notExpiredBy(ft: FormatToken): Boolean = ft.right.end < endPos
          override def toString: String = s"<$endPos"
        }
    }
    case object On extends End {
      def apply(endPos: Int): WithPos =
        new End.WithPos {
          def notExpiredBy(ft: FormatToken): Boolean = ft.right.end <= endPos
          override def toString: String = s"@$endPos"
        }
    }
  }

}

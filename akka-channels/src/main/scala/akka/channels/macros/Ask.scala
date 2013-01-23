package akka.channels.macros

import akka.channels._
import scala.concurrent.Future
import akka.util.Timeout
import scala.reflect.runtime.{ universe ⇒ ru }
import ru.TypeTag
import scala.reflect.macros.Context
import scala.reflect.api.Universe
import akka.actor.ActorRef
import akka.dispatch.ExecutionContexts
import scala.reflect.api.{ TypeCreator }

object Ask {
  import Helpers._

  def impl[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                type PrefixType = ChannelRef[T]
                                                              })(msg: c.Expr[M]): c.Expr[Future[_]] = {
    import c.universe._

    val chT = weakTypeOf[T]
    val msgT = weakTypeOf[M]
    val out = replyChannels(c.universe)(chT, msgT)
    if (out.isEmpty)
      abort(c, s"This ChannelRef does not support messages of type $msgT")

    reify(askOps(c.prefix.splice.actorRef, msg.splice)(imp[Timeout](c).splice))
  }

  def opsImpl[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                   type PrefixType = AnyOps[M]
                                                                 })(channel: c.Expr[ChannelRef[T]]): c.Expr[Future[_]] = {
    import c.universe._

    val chT = weakTypeOf[T]
    val msgT = weakTypeOf[M]
    val out = replyChannels(c.universe)(chT, msgT)
    if (out.isEmpty)
      abort(c, s"This ChannelRef does not support messages of type $msgT")

    reify(askOps(channel.splice.actorRef, c.prefix.splice.value)(imp[Timeout](c).splice))
  }

  def futureImpl[A, T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                         type PrefixType = FutureOps[M]
                                                                       })(channel: c.Expr[ChannelRef[T]]): c.Expr[Future[_]] = {
    import c.universe._

    val chT = weakTypeOf[T]
    val msgT = weakTypeOf[M]
    val reply = replyChannels(c.universe)(chT, msgT) match {
      case Nil      ⇒ abort(c, s"This ChannelRef does not support messages of type $msgT")
      case x :: Nil ⇒ x
      case xs       ⇒ toChannels(c.universe)(xs)
    }

    implicit val tA = WeakTypeTag[A](c.mirror, new TypeCreator {
      def apply[U <: Universe with Singleton](m: scala.reflect.api.Mirror[U]) = {
        val imp = m.universe.mkImporter(c.universe)
        imp.importType(reply)
      }
    })
    reify(askFuture[M, A](channel.splice.actorRef, c.prefix.splice.future)(imp[Timeout](c).splice))
  }

  @inline def askOps[T](target: ActorRef, msg: Any)(implicit t: Timeout): Future[T] = akka.pattern.ask(target, msg).asInstanceOf[Future[T]]

  def askFuture[T1, T2](target: ActorRef, future: Future[T1])(implicit t: Timeout): Future[T2] =
    future.flatMap(akka.pattern.ask(target, _).asInstanceOf[Future[T2]])(ExecutionContexts.sameThreadExecutionContext)

  def askTree[M](c: Context with Singleton)(msgT: c.universe.Type, chT: c.universe.Type)(target: c.Expr[ActorRef], msg: c.Expr[M]): c.Expr[Future[_]] = {
    import c.universe._
    val out = replyChannels(c.universe)(chT, msgT)
    if (out.isEmpty) {
      c.error(c.enclosingPosition, s"This ChannelRef does not support messages of type $msgT")
      reify(null)
    } else {
      val timeout = c.inferImplicitValue(typeOf[Timeout])
      if (timeout.isEmpty)
        c.error(c.enclosingPosition, s"no implicit akka.util.Timeout found")
      val result = appliedType(weakTypeOf[Future[_]].typeConstructor, List(lub(out)))
      c.Expr(
        TypeApply(
          Select(
            reify(akka.pattern.ask(
              target.splice, msg.splice)(
                c.Expr(timeout)(weakTypeTag[Timeout]).splice)).tree,
            "asInstanceOf"),
          List(TypeTree().setType(result))))(weakTypeTag[Future[_]])
    }
  }

}
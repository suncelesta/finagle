package com.twitter.finagle.factory

import com.twitter.finagle._
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.Trace
import com.twitter.util._
import java.net.SocketAddress
import scala.collection.immutable

/**
 * Proxies requests to the current definiton of 'name', queueing
 * requests while it is pending.
 */
private class DynNameFactory[Req, Rep](
    name: Activity[Name.Bound],
    newService: (Name.Bound, ClientConnection) => Future[Service[Req, Rep]],
    trace: Try[Name.Bound] => Unit)
  extends ServiceFactory[Req, Rep] {

  private sealed trait State
  private case class Pending(q: immutable.Queue[(ClientConnection, Promise[Service[Req, Rep]])])
    extends State
  private case class Named(name: Name.Bound) extends State
  private case class Failed(exc: Throwable) extends State
  private case object Closed extends State

  private case class NamingException(exc: Throwable) extends Exception(exc)

  @volatile private[this] var state: State = Pending(immutable.Queue.empty)

  private[this] val sub = name.run.changes respond {
    case Activity.Ok(name) => synchronized {
      state match {
        case Pending(q) =>
          state = Named(name)
          for ((conn, p) <- q) p.become(apply(conn))
        case Failed(_) | Named(_) =>
          state = Named(name)
        case Closed =>
      }
    }

    case Activity.Failed(exc) => synchronized {
      state match {
        case Pending(q) =>
          // wrap the exception in a NamingException, so that it can
          // be recovered for tracing
          for ((_, p) <- q) p.setException(NamingException(exc))
          state = Failed(exc)
        case Failed(_) =>
          state = Failed(exc)
        case Named(_) | Closed =>
      }
    }

    case Activity.Pending =>
  }

  def apply(conn: ClientConnection) = {
    val result = state match {
      case Named(name) =>
        val rtnName = Return(name)
        // attach the trace each time the service is used
        newService(name, conn) map { service =>
          new ServiceProxy(service) {
            override def apply(request: Req) = {
              trace(rtnName)
              super.apply(request)
            }
          }
        }

      // don't trace these, since they're not a namer failure
      case Closed => Future.exception(new ServiceClosedException)

      case Failed(exc) =>
        trace(Throw(exc))
        Future.exception(exc)

      case Pending(_) => applySync(conn)
    }

    result rescue {
      // extract the underlying exception, to trace and return
      case NamingException(t) =>
        trace(Throw(t))
        Future.exception(t)
    }
  }

  private[this] def applySync(conn: ClientConnection) = synchronized {
    state match {
      case Pending(q) =>
        val p = new Promise[Service[Req, Rep]]
        val el = (conn, p)
        p setInterruptHandler { case exc =>
          synchronized {
            state match {
              case Pending(q) if q contains el =>
                state = Pending(q filter (_ != el))
                p.setException(new CancelledConnectionException(exc))
              case _ =>
            }
          }
        }
        state = Pending(q enqueue el)
        p

      case other => apply(conn)
    }
  }

  def close(deadline: Time) = {
    val prev = synchronized {
      val prev = state
      state = Closed
      prev
    }
    prev match {
      case Pending(q) =>
        val exc = new ServiceClosedException
        for ((_, p) <- q)
          p.setException(exc)
      case _ =>
    }
    sub.close(deadline)
  }
}

private[finagle] class NameTracer(
    path: Path,
    base: Dtab,
    local: Dtab,
    trace: (String, Any) => Unit = Trace.recordBinary)
  extends (Try[Name.Bound] => Unit) {

  def apply(nameTry: Try[Name.Bound]): Unit = {
    trace("wily.path", path.show)
    trace("wily.dtab.base", base.show)
    trace("wily.dtab.local", local.show)
    nameTry match {
      case Return(name) => trace("wily.name", name.id)
      case Throw(exc) => trace("wily.failure", exc.getClass.getSimpleName)
    }
  }
}

/**
 * A factory that routes to the local binding of the passed-in
 * [[com.twitter.finagle.Name.Path Name.Path]]. It calls `newFactory`
 * to mint a new [[com.twitter.finagle.ServiceFactory
 * ServiceFactory]] for novel name evaluations.
 *
 * A two-level caching scheme is employed for efficiency:
 *
 * First, Name-trees are evaluated by the default evaluation
 * strategy, which produces a set of [[com.twitter.finagle.Name.Bound
 * Name.Bound]]; these name-sets are cached individually so that they
 * can be reused. Should different name-tree evaluations yield the
 * same name-set, they will use the same underlying (cached) factory.
 *
 * Secondly, in order to avoid evaluating names unnecessarily, we
 * also cache the evaluation relative to a [[com.twitter.finagle.Dtab
 * Dtab]]. This is done to short-circuit the evaluation process most
 * of the time (as we expect most requests to share a namer).
 *
 * @bug This is far too complicated, though it seems necessary for
 * efficiency when namers are occasionally overriden.
 *
 * @bug 'isAvailable' has a funny definition.
 */
private[finagle] class BindingFactory[Req, Rep](
    path: Path,
    newFactory: Var[Addr] => ServiceFactory[Req, Rep],
    statsReceiver: StatsReceiver = NullStatsReceiver,
    maxNameCacheSize: Int = 8,
    maxNamerCacheSize: Int = 4)
  extends ServiceFactory[Req, Rep] {

  private[this] val tree = NameTree.Leaf(path)

  private[this] val nameCache =
    new ServiceFactoryCache[Name.Bound, Req, Rep](
      bound => newFactory(bound.addr),
      statsReceiver.scope("namecache"),
      maxNameCacheSize)

  private[this] val noBrokersAvailableException =
    new NoBrokersAvailableException(path.show)

  private[this] val dtabCache = {
    val newFactory: ((Dtab, Dtab)) => ServiceFactory[Req, Rep] = { case (base, local) =>
      val namer = (base ++ local) orElse Namer.global
      val name: Activity[Name.Bound] = namer.bind(tree).map(_.eval) flatMap {
        case None => Activity.exception(noBrokersAvailableException)
        case Some(set) if set.isEmpty => Activity.exception(noBrokersAvailableException)
        case Some(set) if set.size == 1 => Activity.value(set.head)
        case Some(set) => Activity.value(Name.all(set))
      }

      val tracer = new NameTracer(path, base, local)

      new DynNameFactory(name, nameCache.apply, tracer)
    }

    new ServiceFactoryCache[(Dtab, Dtab), Req, Rep](
      newFactory,
      statsReceiver.scope("dtabcache"),
      maxNamerCacheSize)
  }

  def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
    val service = dtabCache((Dtab.base, Dtab.local), conn)
    val localDtab = Dtab.local
    if (localDtab.isEmpty) service
    else service transform {
      case Throw(e: NoBrokersAvailableException) =>
        Future.exception(new NoBrokersAvailableException(e.name, localDtab))
      case Throw(e) =>
        Future.exception(e)
      case Return(service) =>
        Future.value(new SimpleFilter[Req, Rep] {
          def apply(req: Req, service: Service[Req, Rep]): Future[Rep] =
            service(req) rescue { case e: NoBrokersAvailableException =>
              Future.exception(new NoBrokersAvailableException(e.name, localDtab))
            }
        } andThen service)
    }
  }

  def close(deadline: Time) =
    Closable.sequence(dtabCache, nameCache).close(deadline)

  override def isAvailable = dtabCache.isAvailable
}

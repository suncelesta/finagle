package com.twitter.finagle

import com.twitter.finagle.client._
import com.twitter.finagle.netty3._
import com.twitter.finagle.pool.SingletonPool
import com.twitter.finagle.server._
import com.twitter.finagle.stats.StatsReceiver
import java.net.SocketAddress
import org.jboss.netty.buffer.{ChannelBuffer => CB}

object MuxTransporter extends Netty3Transporter[CB, CB](
  "mux", mux.PipelineFactory)

object MuxClient extends MuxClient
class MuxClient extends DefaultClient[CB, CB](
  name = "mux",
  endpointer = (sa, sr) =>
    (mux.lease.LeasedBridge[CB, CB, CB, CB](
      MuxTransporter, new mux.ClientDispatcher(_, sr))(sa, sr)),
  pool = (sr: StatsReceiver) => new SingletonPool(_, sr.scope("reusingpool"))
)

object MuxListener extends Netty3Listener[CB, CB]("mux", mux.PipelineFactory)
object MuxServer extends DefaultServer[CB, CB, CB, CB](
  "mux", MuxListener, new mux.ServerDispatcher(_, _, true)
)

package exp {
  private[finagle] object MuxTransporter {
    def apply(prms: Stack.Params): Transporter[CB, CB] =
      Netty3Transporter(mux.PipelineFactory, prms)
  }

  private[finagle] object MuxClient extends StackClient[CB, CB](
    // LeasedFactory.module needs to be directly before the stack endpoint.
    StackClient.newStack
      .replace(StackClient.Role.pool, SingletonPool.module[CB, CB])
      .replace(StackClient.Role.prepConn, mux.lease.LeasedFactory.module[CB, CB]),
    Stack.Params.empty) {
    protected type In = CB
    protected type Out = CB
    protected val newTransporter = MuxTransporter(_)
    protected val newDispatcher: Stack.Params => Dispatcher = { prms =>
      val param.Stats(sr) = prms[param.Stats]
      trans => new mux.ClientDispatcher(trans.cast[CB, CB], sr)
    }
  }

  private[finagle] object MuxListener {
    def apply(prms: Stack.Params): Listener[CB, CB] =
      Netty3Listener(mux.PipelineFactory, prms)
  }

  private[finagle] object MuxServer extends StackServer[CB, CB] {
    protected type In = CB
    protected type Out = CB
    protected val newListener = MuxListener(_)
    protected val newDispatcher: Stack.Params => Dispatcher =
      Function.const(new mux.ServerDispatcher(_, _, true, mux.lease.exp.ClockedDrainer.flagged))
  }
}

/**
 * A client and server for the mux protocol described in [[com.twitter.finagle.mux]].
 */
object Mux extends Client[CB, CB] with Server[CB, CB] {
  def newClient(dest: Name, label: String): ServiceFactory[CB, CB] =
    exp.MuxClient.newClient(dest, label)

  def serve(addr: SocketAddress, service: ServiceFactory[CB, CB]): ListeningServer =
    exp.MuxServer.serve(addr, service)
}

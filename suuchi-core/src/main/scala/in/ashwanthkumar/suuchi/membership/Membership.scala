package in.ashwanthkumar.suuchi.membership

import java.io.File
import java.time.Duration
import java.util.function.Consumer

import io.atomix.AtomixReplica
import io.atomix.catalyst.transport.Address
import io.atomix.catalyst.transport.netty.NettyTransport
import io.atomix.copycat.server.storage.{Storage, StorageLevel}
import io.atomix.group.{GroupMember, LocalMember}
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

abstract class Membership(host: String, port: Int) {
  def bootstrap(bootstrapper: Bootstrapper): Membership

  def start(): Membership

  def stop(): Unit

  def onJoin: Member => Unit

  def onLeave: Member => Unit

  def nodes: Iterable[Member]
}

class AtomixMembership(host: String, port: Int, workDir: String, clusterIdentifier: String) extends Membership(host, port) {
  private val log = LoggerFactory.getLogger(classOf[AtomixMembership])

  var atomix = AtomixReplica.builder(new Address(host, port))
    .withTransport(NettyTransport.builder().build())
    .withStorage(
      Storage.builder()
        .withDirectory(new File(workDir, host + "_" + port))
        .withStorageLevel(StorageLevel.DISK)
        .withMinorCompactionInterval(Duration.ofSeconds(30))
        .withMajorCompactionInterval(Duration.ofMinutes(10))
        .withFlushOnCommit()
        .build()
    )
    .build()

  var me: LocalMember = _

  override def bootstrap(bootstrapper: Bootstrapper): AtomixMembership = {
    if (bootstrapper.nodes.isEmpty) {
      atomix = atomix.bootstrap().join()
    } else {
      atomix = atomix.join(bootstrapper.nodes.map(m => new Address(m.host, m.port))).join()
    }
    this
  }

  override def start(): AtomixMembership = {
    val group = atomix.getGroup(clusterIdentifier).join()
    me = group.join().join()
    // register a shutdown hook right away
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        me.leave()
      }
    })

    group.onJoin(new Consumer[GroupMember] {
      override def accept(t: GroupMember): Unit = onJoin.apply(Member(t.id()))
    })

    group.onLeave(new Consumer[GroupMember] {
      override def accept(t: GroupMember): Unit = onLeave.apply(Member(t.id()))
    })

    this
  }

  override def onJoin: (Member) => Unit = (m: Member) => {
    log.info(s"$m has joined")
  }
  override def onLeave: (Member) => Unit = (m: Member) => {
    log.info(s"$m has left")
  }

  override def nodes: Iterable[Member] = {
    atomix.getGroup(clusterIdentifier).get().members().map(t => Member(t.id))
  }

  override def stop(): Unit = {
    me.leave().join()
  }
}
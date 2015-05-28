/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.serialization

import scala.collection.immutable
import com.typesafe.config._
import akka.actor._
import akka.persistence._
import akka.serialization._
import akka.testkit._

import akka.persistence.AtLeastOnceDelivery.AtLeastOnceDeliverySnapshot
import akka.persistence.AtLeastOnceDelivery.UnconfirmedDelivery
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.apache.commons.codec.binary.Hex.decodeHex

object SerializerSpecConfigs {
  val customSerializers = ConfigFactory.parseString(
    """
      akka.actor {
        serializers {
          my-payload = "akka.persistence.serialization.MyPayloadSerializer"
          my-payload2 = "akka.persistence.serialization.MyPayload2Serializer"
          my-snapshot = "akka.persistence.serialization.MySnapshotSerializer"
          my-snapshot2 = "akka.persistence.serialization.MySnapshotSerializer2"
          old-payload = "akka.persistence.serialization.OldPayloadSerializer"
        }
        serialization-bindings {
          "akka.persistence.serialization.MyPayload" = my-payload
          "akka.persistence.serialization.MyPayload2" = my-payload2
          "akka.persistence.serialization.MySnapshot" = my-snapshot
          "akka.persistence.serialization.MySnapshot2" = my-snapshot2
          # this entry was used when creating the data for the test
          # "deserialize data when class is removed"
          #"akka.persistence.serialization.OldPayload" = old-payload
        }
      }
    """)

  val remote = ConfigFactory.parseString(
    """
      akka {
        actor {
          provider = "akka.remote.RemoteActorRefProvider"
        }
        remote {
          enabled-transports = ["akka.remote.netty.tcp"]
          netty.tcp {
            hostname = "127.0.0.1"
            port = 0
          }
        }
        loglevel = ERROR
        log-dead-letters = 0
        log-dead-letters-during-shutdown = off
      }
    """)

  def config(configs: String*): Config =
    configs.foldLeft(ConfigFactory.empty)((r, c) ⇒ r.withFallback(ConfigFactory.parseString(c)))
}

import SerializerSpecConfigs._

class SnapshotSerializerPersistenceSpec extends AkkaSpec(customSerializers) {
  val serialization = SerializationExtension(system)

  "A snapshot serializer" must {
    "handle custom snapshot Serialization" in {
      val wrapped = Snapshot(MySnapshot("a"))
      val serializer = serialization.findSerializerFor(wrapped)

      val bytes = serializer.toBinary(wrapped)
      val deserialized = serializer.fromBinary(bytes, None)

      deserialized should ===(Snapshot(MySnapshot(".a.")))
    }

    "handle custom snapshot Serialization with string manifest" in {
      val wrapped = Snapshot(MySnapshot2("a"))
      val serializer = serialization.findSerializerFor(wrapped)

      val bytes = serializer.toBinary(wrapped)
      val deserialized = serializer.fromBinary(bytes, None)

      deserialized should ===(Snapshot(MySnapshot2(".a.")))
    }

    "be able to read snapshot created with akka 2.3.6 and Scala 2.10" in {
      val dataStr = "abc"
      val snapshot = Snapshot(dataStr.getBytes("utf-8"))
      val serializer = serialization.findSerializerFor(snapshot)

      // the oldSnapshot was created with Akka 2.3.6 and it is using JavaSerialization
      // for the SnapshotHeader. See issue #16009.
      // It was created with:
      // println(s"encoded snapshot: " + String.valueOf(encodeHex(serializer.toBinary(snapshot))))
      val oldSnapshot = // 32 bytes per line
        "a8000000aced00057372002d616b6b612e70657273697374656e63652e736572" +
          "69616c697a6174696f6e2e536e617073686f7448656164657200000000000000" +
          "0102000249000c73657269616c697a657249644c00086d616e69666573747400" +
          "0e4c7363616c612f4f7074696f6e3b7870000000047372000b7363616c612e4e" +
          "6f6e6524465024f653ca94ac0200007872000c7363616c612e4f7074696f6ee3" +
          "6024a8328a45e90200007870616263"

      val bytes = decodeHex(oldSnapshot.toCharArray)
      val deserialized = serializer.fromBinary(bytes, None).asInstanceOf[Snapshot]

      val deserializedDataStr = new String(deserialized.data.asInstanceOf[Array[Byte]], "utf-8")
      dataStr should ===(deserializedDataStr)
    }

    "be able to read snapshot created with akka 2.3.6 and Scala 2.11" in {
      val dataStr = "abc"
      val snapshot = Snapshot(dataStr.getBytes("utf-8"))
      val serializer = serialization.findSerializerFor(snapshot)

      // the oldSnapshot was created with Akka 2.3.6 and it is using JavaSerialization
      // for the SnapshotHeader. See issue #16009.
      // It was created with:
      // println(s"encoded snapshot: " + String.valueOf(encodeHex(serializer.toBinary(snapshot))))
      val oldSnapshot = // 32 bytes per line
        "a8000000aced00057372002d616b6b612e70657273697374656e63652e736572" +
          "69616c697a6174696f6e2e536e617073686f7448656164657200000000000000" +
          "0102000249000c73657269616c697a657249644c00086d616e69666573747400" +
          "0e4c7363616c612f4f7074696f6e3b7870000000047372000b7363616c612e4e" +
          "6f6e6524465024f653ca94ac0200007872000c7363616c612e4f7074696f6efe" +
          "6937fddb0e66740200007870616263"

      val bytes = decodeHex(oldSnapshot.toCharArray)
      val deserialized = serializer.fromBinary(bytes, None).asInstanceOf[Snapshot]

      val deserializedDataStr = new String(deserialized.data.asInstanceOf[Array[Byte]], "utf-8")
      dataStr should ===(deserializedDataStr)
    }
  }
}

class MessageSerializerPersistenceSpec extends AkkaSpec(customSerializers) {
  val serialization = SerializationExtension(system)

  "A message serializer" when {
    "not given a manifest" must {
      "handle custom Persistent message serialization" in {
        val persistent = PersistentRepr(MyPayload("a"), 13, "p1", true, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, None)

        deserialized should ===(persistent.withPayload(MyPayload(".a.")))
      }
    }

    "given a PersistentRepr manifest" must {
      "handle custom Persistent message serialization" in {
        val persistent = PersistentRepr(MyPayload("b"), 13, "p1", true, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[PersistentRepr]))

        deserialized should ===(persistent.withPayload(MyPayload(".b.")))
      }
    }

    "given payload serializer with string manifest" must {
      "handle serialization" in {
        val persistent = PersistentRepr(MyPayload2("a", 17), 13, "p1", true, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, None)

        deserialized should ===(persistent.withPayload(MyPayload2(".a.", 17)))
      }

      "be able to evolve the data types" in {
        val oldEvent = MyPayload("a")
        val serializer1 = serialization.findSerializerFor(oldEvent)
        val bytes = serializer1.toBinary(oldEvent)

        // now the system is updated to version 2 with new class MyPayload2
        // and MyPayload2Serializer that handles migration from old MyPayload
        val serializer2 = serialization.serializerFor(classOf[MyPayload2])
        val deserialized = serializer2.fromBinary(bytes, Some(oldEvent.getClass))

        deserialized should be(MyPayload2(".a.", 0))
      }

      "be able to deserialize data when class is removed" in {
        val serializer = serialization.findSerializerFor(PersistentRepr("x", 13, "p1", true, testActor))

        // It was created with:
        // val old = PersistentRepr(OldPayload('A'), 13, "p1", true, testActor)
        // import org.apache.commons.codec.binary.Hex._
        // println(s"encoded OldPayload: " + String.valueOf(encodeHex(serializer.toBinary(old))))
        //
        val oldData =
          "0a3e08c7da04120d4f6c645061796c6f61642841291a2" +
            "9616b6b612e70657273697374656e63652e7365726961" +
            "6c697a6174696f6e2e4f6c645061796c6f6164100d1a0" +
            "2703120015a45616b6b613a2f2f4d6573736167655365" +
            "7269616c697a657250657273697374656e63655370656" +
            "32f73797374656d2f746573744163746f722d31233133" +
            "3137373931343033"

        // now the system is updated, OldPayload is replaced by MyPayload, and the
        // OldPayloadSerializer is adjusted to migrate OldPayload
        val bytes = decodeHex(oldData.toCharArray)

        val deserialized = serializer.fromBinary(bytes, None).asInstanceOf[PersistentRepr]

        deserialized.payload should be(MyPayload("OldPayload(A)"))
      }
    }

    "given AtLeastOnceDeliverySnapshot" must {
      "handle empty unconfirmed" in {
        val unconfirmed = Vector.empty
        val snap = AtLeastOnceDeliverySnapshot(13, unconfirmed)
        val serializer = serialization.findSerializerFor(snap)

        val bytes = serializer.toBinary(snap)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[AtLeastOnceDeliverySnapshot]))

        deserialized should ===(snap)
      }

      "handle a few unconfirmed" in {
        val unconfirmed = Vector(
          UnconfirmedDelivery(deliveryId = 1, destination = testActor.path, "a"),
          UnconfirmedDelivery(deliveryId = 2, destination = testActor.path, "b"),
          UnconfirmedDelivery(deliveryId = 3, destination = testActor.path, 42))
        val snap = AtLeastOnceDeliverySnapshot(17, unconfirmed)
        val serializer = serialization.findSerializerFor(snap)

        val bytes = serializer.toBinary(snap)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[AtLeastOnceDeliverySnapshot]))

        deserialized should ===(snap)
      }
    }

  }
}

object MessageSerializerRemotingSpec {
  class LocalActor(port: Int) extends Actor {
    def receive = {
      case m ⇒ context.actorSelection(s"akka.tcp://remote@127.0.0.1:${port}/user/remote") tell (m, sender())
    }
  }

  class RemoteActor extends Actor {
    def receive = {
      case PersistentRepr(MyPayload(data), _) ⇒ sender() ! s"p${data}"
    }
  }

  def port(system: ActorSystem) =
    address(system).port.get

  def address(system: ActorSystem) =
    system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
}

class MessageSerializerRemotingSpec extends AkkaSpec(remote.withFallback(customSerializers)) with ImplicitSender with DefaultTimeout {
  import MessageSerializerRemotingSpec._

  val remoteSystem = ActorSystem("remote", remote.withFallback(customSerializers))
  val localActor = system.actorOf(Props(classOf[LocalActor], port(remoteSystem)), "local")

  override protected def atStartup() {
    remoteSystem.actorOf(Props[RemoteActor], "remote")
  }

  override def afterTermination() {
    Await.ready(remoteSystem.terminate(), Duration.Inf)
  }

  "A message serializer" must {
    "custom-serialize Persistent messages during remoting" in {
      localActor ! PersistentRepr(MyPayload("a"))
      expectMsg("p.a.")
    }
  }
}

final case class MyPayload(data: String)
final case class MyPayload2(data: String, n: Int)
final case class MySnapshot(data: String)
final case class MySnapshot2(data: String)

// this class was used when creating the data for the test
// "deserialize data when class is removed"
//final case class OldPayload(c: Char)

class MyPayloadSerializer extends Serializer {
  val MyPayloadClass = classOf[MyPayload]

  def identifier: Int = 77123
  def includeManifest: Boolean = true

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case MyPayload(data) ⇒ s".${data}".getBytes("UTF-8")
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = manifest match {
    case Some(MyPayloadClass) ⇒ MyPayload(s"${new String(bytes, "UTF-8")}.")
    case Some(c)              ⇒ throw new Exception(s"unexpected manifest ${c}")
    case None                 ⇒ throw new Exception("no manifest")
  }
}

class MyPayload2Serializer extends SerializerWithStringManifest {
  val MyPayload2Class = classOf[MyPayload]

  val ManifestV1 = classOf[MyPayload].getName
  val ManifestV2 = "MyPayload-V2"

  def identifier: Int = 77125

  def manifest(o: AnyRef): String = ManifestV2

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case MyPayload2(data, n) ⇒ s".$data:$n".getBytes("UTF-8")
  }

  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case ManifestV2 ⇒
      val parts = new String(bytes, "UTF-8").split(":")
      MyPayload2(data = parts(0) + ".", n = parts(1).toInt)
    case ManifestV1 ⇒
      MyPayload2(data = s"${new String(bytes, "UTF-8")}.", n = 0)
    case other ⇒
      throw new Exception(s"unexpected manifest [$other]")
  }
}

class MySnapshotSerializer extends Serializer {
  val MySnapshotClass = classOf[MySnapshot]

  def identifier: Int = 77124
  def includeManifest: Boolean = true

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case MySnapshot(data) ⇒ s".${data}".getBytes("UTF-8")
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = manifest match {
    case Some(MySnapshotClass) ⇒ MySnapshot(s"${new String(bytes, "UTF-8")}.")
    case Some(c)               ⇒ throw new Exception(s"unexpected manifest ${c}")
    case None                  ⇒ throw new Exception("no manifest")
  }
}

class MySnapshotSerializer2 extends SerializerWithStringManifest {
  val CurrentManifest = "MySnapshot-V2"
  val OldManifest = classOf[MySnapshot].getName

  def identifier: Int = 77126

  def manifest(o: AnyRef): String = CurrentManifest

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case MySnapshot2(data) ⇒ s".${data}".getBytes("UTF-8")
  }

  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case CurrentManifest | OldManifest ⇒
      MySnapshot2(s"${new String(bytes, "UTF-8")}.")
    case other ⇒
      throw new Exception(s"unexpected manifest [$other]")
  }
}

class OldPayloadSerializer extends SerializerWithStringManifest {

  def identifier: Int = 77127
  val OldPayloadClassName = "akka.persistence.serialization.OldPayload"
  val MyPayloadClassName = classOf[MyPayload].getName

  def manifest(o: AnyRef): String = o.getClass.getName

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case MyPayload(data) ⇒ s".${data}".getBytes("UTF-8")
    case old if old.getClass.getName == OldPayloadClassName ⇒
      o.toString.getBytes("UTF-8")
  }

  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case OldPayloadClassName ⇒
      MyPayload(new String(bytes, "UTF-8"))
    case MyPayloadClassName ⇒ MyPayload(s"${new String(bytes, "UTF-8")}.")
    case other ⇒
      throw new Exception(s"unexpected manifest [$other]")
  }
}

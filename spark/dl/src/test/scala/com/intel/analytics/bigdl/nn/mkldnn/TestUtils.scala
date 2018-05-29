/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.nn.mkldnn

import java.io.{File, PrintWriter}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.nio.{ByteBuffer, ByteOrder}

import com.intel.analytics.bigdl.Module
import com.intel.analytics.bigdl.nn.Container
import com.intel.analytics.bigdl.nn.abstractnn.TensorModule
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.tensor.{Storage, Tensor}

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.sys.process._

object Tools {
  def error[@specialized(Float, Double) T: ClassTag](tensor1: Tensor[T], tensor2: Tensor[T])(
      implicit ev: TensorNumeric[T]): Double = {
    require(tensor1.nElement() == tensor2.nElement())
    var ret = 0.0
    val storage1 = tensor1.storage().array()
    val storage2 = tensor2.storage().array()
    for (i <- 0 until tensor1.nElement()) {
      ret += math.abs(
        ev.toType[Double](storage1(i)) - ev.toType[Double](storage2(i)))
    }
    ret
  }

  def cumulativeError[T: ClassTag](tensor1: Tensor[T], tensor2: Tensor[T], msg: String)(
      implicit ev: TensorNumeric[T]): Double = {
    val ret = error[T](tensor1, tensor2)
    println((msg, "CUMULATIVE ERROR:", ret).productIterator.mkString(" ").toUpperCase)
    ret
  }

  def averageError[T: ClassTag](tensor1: Tensor[T], tensor2: Tensor[T], msg: String)(
      implicit ev: TensorNumeric[T]): Double = {
    require(tensor1.nElement() > 0)
    val ret = error[T](tensor1, tensor2) / tensor1.nElement()
    println((msg, "AVERAGE ERROR:", ret).productIterator.mkString(" ").toUpperCase)
    ret
  }

  def averageError[T: ClassTag](m1: Map[String, Tensor[T]],
                                m2: Map[String, Tensor[T]],
                                err: Map[String, Double])(implicit ev: TensorNumeric[T]): Unit = {
    require(m1.keySet == m2.keySet)
    require(m1.keySet subsetOf err.keySet)

    m1.keySet.foreach(i => {
      val err = error(m1(i), m2(i)) / m1(i).nElement()
      printf("%20s = %E\n", i.toUpperCase(), err)
    })
  }

  def averageAllTensors[T: ClassTag](tensor1: Tensor[T], msg: String = "Unknown")(
      implicit ev: TensorNumeric[T]): Unit = {
    val sum = tensor1.storage().array().foldLeft(ev.fromType[Int](0))((l, r) => ev.plus(l, r))
    val num = ev.fromType[Int](tensor1.nElement())
    println(("AVERGE", msg, ev.divide(sum, num)).productIterator.mkString(" ").toUpperCase())
  }

  def printTensor[T: ClassTag](tensor: Tensor[T], num: Int = 16, msg: String = "Unknown")(
      implicit ev: TensorNumeric[T]): Unit = {
    println(msg.toUpperCase)
    for (i <- 0 until num) {
      println((i, ev.toType[Double](tensor.storage().array()(i))).productIterator.mkString("\t"))
    }
  }

  private def fileName(name: String, identity: String): String = {
    val tmpdir = System.getProperty("java.io.tmpdir")
    val dirname = if (tmpdir.endsWith("/")) {
      tmpdir
    } else {
      tmpdir + "/"
    }

    val filename = if (identity.isEmpty) {
      ".bin"
    } else {
      "." + identity + ".bin"
    }

    dirname + name + filename
  }

  /*
   * @brief read binary in tmp dir to Tensor, which is used for comparing
   *        with Intel Caffe with MKL-DNN
   */
  def getTensor(name: String, size: Array[Int], identity: String): Tensor[Float] = {
    val tensor = Tensor[Float]()
    val file = fileName(name, identity)

    if (Files.exists(Paths.get(file))) {
      setTensorFloat()

      def loadData(name: String): ByteBuffer = {
        val fileChannel: FileChannel =
          Files.newByteChannel(Paths.get(name), StandardOpenOption.READ).asInstanceOf[FileChannel]
        val byteBuffer: ByteBuffer = ByteBuffer.allocate(fileChannel.size().toInt)
        byteBuffer.order(ByteOrder.nativeOrder())
        fileChannel.read(byteBuffer)
        byteBuffer.flip()
        byteBuffer
      }


      def setTensorFloat(): Unit = {
        val data = loadData(file).asFloatBuffer()
        val array = new Array[Float](data.limit())
        data.get(array)
        tensor.set(Storage(array), sizes = size)
      }
    }

    tensor
  }

  def flattenModules(model: Module[Float], modules: ArrayBuffer[TensorModule[Float]]): Unit = {
    model match {
      case container : Container[_, _, _] =>
        if (container.modules.nonEmpty) {
          for (i <- container.modules) {
            flattenModules(i.asInstanceOf[Module[Float]], modules)
          }
        }
      case _ =>
        modules += model.asInstanceOf[TensorModule[Float]]
    }
  }

  def randTimes(): Int = 10
}

/**
 * Call "collect" command, which is a method to collect output binary files.
 * It's similar to "caffe collect", the difference is that it supports collect
 * single layers output and gradient through make a fake gradOutput/top_diff.
 */
object Collect {
  val tmpdir = System.getProperty("java.io.tmpdir")
  val collectPath = s"/home/yanzhang/workspace/dl_frameworks/intel.caffe.test/build/tools/collect"

  def hasCollect: Boolean = {
    val exitValue = if (collectPath != null) s"ls $collectPath".! else "which collect".!
    exitValue == 0
  }

  /**
   * save the prototxt to a temporary file and call collect
   * @param prototxt prototxt with string
   * @return the middle random number in temporary file, which is an identity for getTensor.
   */
  def run(prototxt: String, singleLayer: Boolean = true): String = {
    def saveToFile(prototxt: String, name: String): String = {
      val tmpFile = java.io.File.createTempFile(name, ".prototxt")
      val absolutePath = tmpFile.getAbsolutePath

      println(s"prototxt is saved to $absolutePath")

      val writer = new PrintWriter(tmpFile)
      writer.println(prototxt)
      writer.close()

      absolutePath
    }

    if (! hasCollect) {
      throw new RuntimeException(s"Can't find collect command. Have you copy to the PATH?")
    }

    val file = saveToFile(prototxt, "UnitTest.") // UnitTest ends with dot for getting random number
    val identity = file.split("""\.""").reverse(1) // get the random number

    val cmd = Seq(s"$collectPath", "--model", file, "--type", "float", "--identity", identity)
    val exitValue = if (singleLayer) {
      Process(cmd :+ "--single", new File(tmpdir)).!
    } else {
      Process(cmd, new File(tmpdir)).!
    }

    require(exitValue == 0, s"Something wrong with collect command. Please check it.")

    identity
  }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.h2o.sparkling.ml.models

import java.io.File

import scala.collection.JavaConverters._
import _root_.hex.genmodel.algos.targetencoder.TargetEncoderMojoModel
import _root_.hex.genmodel.easy.EasyPredictModelWrapper
import ai.h2o.sparkling.ml.params.H2OTargetEncoderProblemType
import ai.h2o.sparkling.ml.utils.Utils
import org.apache.spark.ml.Model
import org.apache.spark.ml.linalg.{DenseVector, Vector}
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.sql.{DataFrame, Dataset, Row}

class H2OTargetEncoderMOJOModel(override val uid: String)
  extends Model[H2OTargetEncoderMOJOModel]
  with H2OTargetEncoderBase
  with H2OMOJOWritable
  with H2OMOJOFlattenedInput {

  override protected def inputColumnNames: Array[String] = getInputCols().flatten.distinct

  override protected def outputColumnName: String = getClass.getSimpleName + "_output"

  def this() = this(Identifiable.randomUID(getClass.getSimpleName))

  @transient private lazy val inOutMapping: Map[Seq[String], (Seq[String], Int)] = {
    val mojoModel = Utils.getMojoModel(getMojo()).asInstanceOf[TargetEncoderMojoModel]
    mojoModel._inoutMapping.asScala.zipWithIndex.map {
      case (entry, index) => (entry.from.toList, (entry.to.toList, index))
    }.toMap
  }

  override def transform(dataset: Dataset[_]): DataFrame = {
    if (getInputCols().isEmpty) {
      dataset.toDF()
    } else {
      import org.apache.spark.sql.DatasetExtensions._
      val outputCols = getOutputCols()
      val udfWrapper =
        H2OTargetEncoderMOJOUdfWrapper(getMojo, outputCols, H2OTargetEncoderProblemType.valueOf(getProblemType()))
      val withPredictionsDF = applyPredictionUdf(dataset, _ => udfWrapper.mojoUdf)
      withPredictionsDF
        .withColumns(
          outputCols,
          outputCols.zip(getInputCols()).map {
            case (outputCol, inputColGroup) =>
              val (outputColsFromMapping, index) = inOutMapping.getOrElse(inputColGroup.toList, (Seq.empty[String], 0))
              if (outputColsFromMapping.length == 0) {
                throw new RuntimeException(
                  s"The target encoder MOJO model doesn't return any column for the input column group '$outputColsFromMapping'")
              }
              col(outputColumnName)(index) as outputCol
          })
        .drop(outputColumnName)
    }
  }

  override def copy(extra: ParamMap): H2OTargetEncoderMOJOModel = defaultCopy(extra)
}

private[models] case class OutputColumns(columns: Seq[String], totalOrderOfFirstColumnInGroup: Int)

/**
  * The class holds all necessary dependencies of udf that needs to be serialized.
  */
case class H2OTargetEncoderMOJOUdfWrapper(
    mojoGetter: () => File,
    outputCols: Array[String],
    problemType: H2OTargetEncoderProblemType) {

  @transient private lazy val mojoModel = Utils.getMojoModel(mojoGetter()).asInstanceOf[TargetEncoderMojoModel]
  @transient private lazy val easyPredictModelWrapper: EasyPredictModelWrapper = {
    val config = new EasyPredictModelWrapper.Config()
    config.setModel(mojoModel)
    config.setConvertUnknownCategoricalLevelsToNa(true)
    config.setConvertInvalidNumbersToNa(true)
    new EasyPredictModelWrapper(config)
  }

  @transient private lazy val positions: Seq[Int] = mojoModel._inoutMapping.asScala.map(_.to.length).scanLeft(0)(_ + _)

  val mojoUdf: UserDefinedFunction =
    udf[Array[Option[Vector]], Row] { r: Row =>
      val inputRowData = RowConverter.toH2ORowData(r)
      try {
        val prediction = easyPredictModelWrapper.predictTargetEncoding(inputRowData)
        positions
          .sliding(2)
          .map {
            case Seq(current, next) => Some(new DenseVector(prediction.transformations.slice(current, next)).compressed)
          }
          .toArray
      } catch {
        case _: Throwable => outputCols.map(_ => None)
      }
    }
}

object H2OTargetEncoderMOJOModel extends H2OMOJOReadable[H2OTargetEncoderMOJOModel]

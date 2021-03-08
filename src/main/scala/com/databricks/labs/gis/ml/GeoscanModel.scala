/*
 * Copyright 2019 Databricks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.labs.gis.ml

import org.apache.hadoop.fs.Path
import org.apache.spark.SparkContext
import org.apache.spark.ml.Model
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util.{MLReader, MLWriter, _}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}

/**
 * Spark model containing all clusters and their respective H3 tiles
 * @param uid unique identifier set by Spark framework
 * @param shape the collection of clusters and their respective points
 */
class GeoscanModel(override val uid: String, shape: GeoShape) extends Model[GeoscanModel] with GeoscanParams with MLWritable {

  def getUid: String = uid

  def getShape: GeoShape = shape

  def getPrecision: Int = {
    val precision = GeoUtils.getOptimalResolution($(epsilon))
    require(precision.nonEmpty, "Could not infer precision from epsilon value")
    precision.get
  }

  def toGeoJson: String = shape.toGeoJson

  def getTiles(precision: Int, layers: Int = 0): DataFrame = {
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._
    shape.clusters.flatMap(cluster => {
      GeoUtils.polyFill(cluster.points, precision, layers).map(h3 => {
        (cluster.id, f"${h3}%X")
      })
    }).toDF($(predictionCol), "h3")
  }

  override def copy(extra: ParamMap): GeoscanModel = defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = validateAndTransformSchema(schema)

  /**
   * Core logic of our inference. As the core of GEOSCAN logic relies on the use of `H3` polygons,
   * it becomes natural to leverage the same for model inference instead of bringing in extra GIS dependencies for
   * expensive point in polygons queries. Our geospatial query becomes a simple JOIN operation
   * @param dataset the input dataset to enrich with our model
   * @return the original dataset enriched with a cluster ID (or None)
   */
  override def transform(dataset: Dataset[_]): DataFrame = {

    // We find the best precision based on our epsilon value
    val precision = getPrecision
    val precision_B = dataset.sparkSession.sparkContext.broadcast(precision)

    // We load our model as a dataframe
    val clustersDF = getTiles(precision)

    // That we could join to incoming H3 points (created from a simple UDF)
    val to_h3 = udf((lat: Double, lng: Double) => {
      val h3 = H3.instance.geoToH3(lat, lng, precision_B.value)
      f"${h3}%X"
    })

    // Model inference becomes a simple JOIN operation instead of a point in polygon query
    dataset
      .withColumn("h3", to_h3(col($(latitudeCol)), col($(longitudeCol))))
      .join(clustersDF, List("h3"), "left_outer")
      .drop("h3")

  }

  /**
   * We create our own class to persist both data and metadata of our model
   * @return an instance of [[org.apache.spark.ml.util.MLWriter]] interface
   */
  override def write: MLWriter = {
    new GeoscanModel.GeoscanModelWriter(this)
  }
}

/**
 * One of the annoying things in Spark is the fact that most of useful method are private and / or package restricted.
 * In order to save both data and metadata, we have to implement our own [[org.apache.spark.ml.util.MLReader]] interface
 */
object GeoscanModel extends MLReadable[GeoscanModel] {

  /**
   * We tell Spark framework how to deserializa our model using our implementation of [[org.apache.spark.ml.util.MLReader]] interface
   * @return an [[org.apache.spark.ml.util.MLReader]] interface
   */
  override def read: MLReader[GeoscanModel] = new GeoscanModelReader()

  /**
   * We tell Spark framework how to serializa our model using our implementation of [[org.apache.spark.ml.util.MLWriter]] interface
   * This consists in saving metadata as JSON and data as TSV
   * @param instance the model to save
   */
  class GeoscanModelWriter(instance: GeoscanModel) extends MLWriter {
    override protected def saveImpl(path: String): Unit = {
      ModelIOUtils.saveMetadata(instance, path, sc)
      saveData(instance, path, sc)
    }

    /**
     * Given our model, we store data to disk as a simple TSV file
     * @param instance the model to save
     * @param path the path where to store our model (distributed file system)
     * @param sc the spark context, implicitly provided by Spark API
     */
    def saveData(instance: GeoscanModel, path: String, sc: SparkContext): Unit = {
      val dataPath = new Path(path, "data").toString
      val dataJson = instance.getShape.toGeoJson
      //TODO: save natively
      sc.parallelize(Seq(dataJson), 1).saveAsTextFile(dataPath)
    }
  }

  class GeoscanModelReader extends MLReader[GeoscanModel] {
    /**
     * We tell Spark framework how to deserialize our model using our implementation of [[org.apache.spark.ml.util.MLReader]] interface
     * @param path where to load model from
     * @return a configured instance of our [[GeoscanModel]]
     */
    override def load(path: String): GeoscanModel = {
      val metadata = ModelIOUtils.loadMetadata(path, sc)
      val data = loadData(path, sc)
      val instance = new GeoscanModel(metadata.uid, data)
      ModelIOUtils.getAndSetParams(instance, metadata)
      instance
    }

    /**
     * As we stored our model as TSV on distributed file system, we simple read as textFile and collect back to memory
     * @param path where to read data from
     * @param sc the spark context, implicitly provided by Spark API
     * @return all our clusters and their respective tiles
     */
    def loadData(path: String, sc: SparkContext): GeoShape = {
      val dataPath = new Path(path, "data").toString
      GeoShape.fromGeoJson(sc.textFile(dataPath).first())
    }
  }

}
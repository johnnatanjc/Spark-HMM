package org.com.jonas

import java.io.FileInputStream

import breeze.linalg.{DenseMatrix, DenseVector, normalize}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.{UserDefinedFunction, Window}

object CrossValidation {
  /**
    * @param args
    * args(0): Config Properties File
    * @return result
    *
    */
  def main(args: Array[String]): Unit = {
    val log = org.apache.log4j.LogManager.getRootLogger
    val applicationProps = new java.util.Properties()
    val in = new FileInputStream(args(0))
    applicationProps.load(in)
    in.close()

    if (applicationProps.getProperty("generate_logs").equals("true")) {
      Logger.getLogger("org").setLevel(Level.ERROR)
      Logger.getLogger("akka").setLevel(Level.ERROR)
    }

    val sparkSession = SparkSession.builder.appName("Spark-HMM").getOrCreate()

    /**
      * Class 1 seq with error
      * Class 0 seq without error
      */
    val k_folds = applicationProps.getProperty("k_folds").toInt
    log.info("Value of k_folds: " + k_folds)
    val value_M = applicationProps.getProperty("value_M").toInt
    log.info("Value of value_M: " + value_M)
    val value_k = applicationProps.getProperty("value_k").toInt
    log.info("Value of value_k: " + value_k)
    val number_partitions = applicationProps.getProperty("number_partitions").toInt
    log.info("Value of number_partitions: " + number_partitions)
    val value_epsilon = applicationProps.getProperty("value_epsilon").toDouble
    log.info("Value of value_epsilon: " + value_epsilon)
    val max_num_iterations = applicationProps.getProperty("max_num_iterations").toInt
    log.info("Value of max_num_iterations: " + max_num_iterations)

    /**
      * Make info Class 1
      */
    var sampleClass1 = sparkSession.read.csv(applicationProps.getProperty("path_sample_Class1"))
      .sample(withReplacement = false, applicationProps.getProperty("size_sample").toDouble)
      .withColumnRenamed("_c0", "workitem").withColumnRenamed("_c1", "str_obs")
      .select(col("workitem"), col("str_obs"), row_number().over(Window.orderBy(col("workitem"))).alias("rowId"))

    val nClass1 = sampleClass1.count().toInt
    log.info("Value of nClass1: " + nClass1)
    sampleClass1 = set_folds(sampleClass1, nClass1, k_folds)

    /**
      * Make info Class 0
      */
    var sampleClass0 = sparkSession.read.csv(applicationProps.getProperty("path_sample_Class0"))
      .sample(withReplacement = false, applicationProps.getProperty("size_sample").toDouble)
      .withColumnRenamed("_c0", "workitem").withColumnRenamed("_c1", "str_obs")
      .select(col("workitem"), col("str_obs"), row_number().over(Window.orderBy(col("workitem"))).alias("rowId"))

    val nClass0 = sampleClass0.count().toInt
    log.info("Value of nClass0: " + nClass0)
    sampleClass0 = set_folds(sampleClass0, nClass0, k_folds)

    hmm.Utils.writeresult(applicationProps.getProperty("path_result"), "N,TP,FP,FN,TN,sensitivity,specificity,efficiency,error \n")

    (0 until k_folds).foreach(iter => {
      log.info("*****************************************************************************************")
      log.info("Fold number: " + iter)
      log.info("Getting data to train Class 1")
      val trainClass1 = sampleClass1.where("kfold <> " + iter).drop("kfold", "rowId")
      log.info("Getting data to train Class 0")
      val trainClass0 = sampleClass0.where("kfold <> " + iter).drop("kfold", "rowId")
      log.info("Getting data to validate Class 1")
      val validClass1 = sampleClass1.where("kfold == " + iter).drop("kfold", "rowId")
      log.info("Getting data to validate Class 0")
      val validClass0 = sampleClass0.where("kfold == " + iter).drop("kfold", "rowId")

      log.info("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
      log.info("Start training Class 1")
      val modelClass1 = hmm.BaumWelchAlgorithm.run1(trainClass1, value_M, value_k,
        normalize(DenseVector.rand(value_M), 1.0),
        hmm.Utils.mkstochastic(DenseMatrix.rand(value_M, value_k)),
        hmm.Utils.mkstochastic(DenseMatrix.rand(value_M, value_k)),
        number_partitions, value_epsilon, max_num_iterations)
      log.info("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")

      log.info("Start training Class 0")
      val modelClass0 = hmm.BaumWelchAlgorithm.run1(trainClass0, value_M, value_k,
        normalize(DenseVector.rand(value_M), 1.0),
        hmm.Utils.mkstochastic(DenseMatrix.rand(value_M, value_k)),
        hmm.Utils.mkstochastic(DenseMatrix.rand(value_M, value_k)),
        number_partitions, value_epsilon, max_num_iterations)
      log.info("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")

      val resultClass1 =
        hmm.BaumWelchAlgorithm.validate(validClass1, value_M, value_k, modelClass1._1, modelClass1._2, modelClass1._3)
          .withColumnRenamed("prob", "probMod1").as("valMod1")
          .join(
            hmm.BaumWelchAlgorithm.validate(validClass1, value_M, value_k, modelClass0._1, modelClass0._2, modelClass0._3)
              .withColumnRenamed("prob", "probMod0").as("valMod0"),
            col("valMod1.workitem") === col("valMod0.workitem"), "inner")

      val resultClass0 =
        hmm.BaumWelchAlgorithm.validate(validClass0, value_M, value_k, modelClass1._1, modelClass1._2, modelClass1._3)
          .withColumnRenamed("prob", "probMod1").as("valMod1")
          .join(
            hmm.BaumWelchAlgorithm.validate(validClass0, value_M, value_k, modelClass0._1, modelClass0._2, modelClass0._3)
              .withColumnRenamed("prob", "probMod0").as("valMod0"),
            col("valMod1.workitem") === col("valMod0.workitem"), "inner")

      /** N value */
      log.info("Compute N")
      val N: Double = validClass1.count + validClass0.count
      log.info("Value of N: " + N)

      /** True Positives */
      log.info("Compute True Positives")
      val TP: Double = resultClass1.where("probMod1 > probMod0").count
      log.info("Value of TP: " + TP)

      /** False Positives */
      log.info("Compute False Positives")
      val FP: Double = resultClass0.where("probMod1 > probMod0").count
      log.info("Value of FP: " + FP)

      /** False Negatives */
      log.info("Compute False Negatives")
      val FN: Double = resultClass1.where("probMod1 < probMod0").count
      log.info("Value of FN: " + FN)

      /** True Negatives */
      log.info("Compute True Negatives")
      val TN: Double = resultClass0.where("probMod1 < probMod0").count
      log.info("Value of TN: " + TN)

      /** sensitivity */
      log.info("Compute Sensitivity")
      val sensi: Double = TP / (TP + FN)
      log.info("Value of sensi: " + sensi)

      /** specificity */
      log.info("Compute Specificity")
      val speci: Double = TN / (TN + FP)
      log.info("Value of speci: " + speci)

      /** efficiency */
      log.info("Compute Efficiency")
      val effic: Double = (TP + TN) / N
      log.info("Value of effic: " + effic)

      /** error */
      log.info("Compute Error")
      val error: Double = 1 - effic
      log.info("Value of error: " + error)

      log.info("*****************************************************************************************")

      hmm.Utils.writeresult(applicationProps.getProperty("path_result"), N + "," + TP + "," + FP + "," + FN + "," + TN + "," + sensi + "," + speci + "," + effic + "," + error + " \n")
    })
    sparkSession.stop()
  }

  def set_folds(sample: DataFrame, n: Int, kfolds: Int): DataFrame = {
    val randomList = scala.util.Random.shuffle((0 until n).toList)
    val indexList = Array.fill[Int](n)(0)
    (1 until kfolds).foreach(i => (i until n by kfolds).foreach(j => indexList(randomList(j)) = i))

    val udf_setfold: UserDefinedFunction = udf((rowId: Int) => indexList(rowId - 1))
    sample.withColumn("kfold", udf_setfold(col("rowId")))
  }

}
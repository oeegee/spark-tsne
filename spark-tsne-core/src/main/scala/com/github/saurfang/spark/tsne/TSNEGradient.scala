package com.github.saurfang.spark.tsne

import breeze.linalg._
import breeze.numerics._
import org.apache.spark.Logging

object TSNEGradient extends Logging  {
  /**
   *
   * @param idx
   * @param Y
   * @return
   */
  def computeNumerator(Y: DenseMatrix[Double], idx: Int *): DenseMatrix[Double] = {
    // Y_sum = ||Y_i||^2
    val sumY = sum(pow(Y, 2).apply(*, ::)) // n * 1
    val subY = Y(idx, ::).toDenseMatrix // k * 1
    val y1: DenseMatrix[Double] = Y * (-2.0 :* subY.t) // n * k
    val num: DenseMatrix[Double] = (y1(::, *) + sumY).t // k * n
    num := 1.0 :/ (1.0 :+ (num(::, *) + sumY(idx).toDenseVector)) // k * n

    idx.indices.foreach(i => num.unsafeUpdate(i, idx(i), 0.0)) // num(i, i) = 0

    num
  }

  /**
   * Compute the TSNE Gradient at i. Update the gradient through dY then return costs attributed at i.
   *
   * @param data data point for row i by list of pair of (j, p_ij) and 0 <= j < n
   * @param Y current Y [n * 2]
   * @param totalNum the common numerator that captures the t-distribution of Y
   * @param dY gradient of Y
   * @return loss attributed to row i
   */
  def compute(
               data: Array[(Int, Iterable[(Int, Double)])],
               Y: DenseMatrix[Double],
               num: DenseMatrix[Double],
               totalNum: Double,
               dY: DenseMatrix[Double],
               exaggeration: Boolean): Double = {
    // q = (1 + ||Y_i - Y_j||^2)^-1 / sum(1 + ||Y_k - Y_l||^2)^-1
    val q: DenseMatrix[Double] = num / totalNum
    q.foreachPair{case ((i, j), v) => q.unsafeUpdate(i, j, math.max(v, 1e-12))}

    // q = q - p
    val loss = data.zipWithIndex.flatMap {
      case ((_, itr), i) =>
        itr.map{
          case (j, p) =>
            val exaggeratedP = if(exaggeration) p * 4 else p
            val qij = q(i, j)
            val l = exaggeratedP * math.log(exaggeratedP / qij)
            q.unsafeUpdate(i, j,  qij - exaggeratedP)
            if(l.isNaN) 0.0 else l
        }
    }.sum

    // l = [ (p_ij - q_ij) * (1 + ||Y_i - Y_j||^2)^-1 ]
    q :*= -num
    // l_sum = [0 0 ... sum(l) ... 0]
    sum(q(*, ::)).foreachPair{ case (i, v) => q.unsafeUpdate(i, data(i)._1, q(i, data(i)._1) - v) }

    // dY_i = -4 * (l - l_sum) * Y
    val dYi: DenseMatrix[Double] = -4.0 :* (q * Y)
    data.map(_._1).zipWithIndex.foreach{
      case (i, idx) => dY(i, ::) := dYi(idx, ::)
    }

    loss
  }
}

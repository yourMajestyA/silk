/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.fuberlin.wiwiss.silk.learning.active

import de.fuberlin.wiwiss.silk.config.{LinkSpecification, Prefixes, RuntimeConfig}
import de.fuberlin.wiwiss.silk.dataset.DataSource
import de.fuberlin.wiwiss.silk.entity.{Entity, Index, Link, Path}
import de.fuberlin.wiwiss.silk.execution.GenerateLinks
import de.fuberlin.wiwiss.silk.linkagerule.LinkageRule
import de.fuberlin.wiwiss.silk.linkagerule.input.PathInput
import de.fuberlin.wiwiss.silk.linkagerule.similarity.SimilarityOperator
import de.fuberlin.wiwiss.silk.plugins.distance.equality.EqualityMetric
import de.fuberlin.wiwiss.silk.runtime.activity.{Activity, ActivityContext}
import de.fuberlin.wiwiss.silk.util.RandomUtils._
import de.fuberlin.wiwiss.silk.util.{DPair, Identifier}

import scala.util.Random
import scala.xml.Node

private class GeneratePool(inputs: Seq[DataSource],
                           linkSpec: LinkSpecification,
                           paths: DPair[Seq[Path]]) extends Activity[Seq[Link]] {

  private val runtimeConfig = RuntimeConfig(partitionSize = 100, useFileCache = false, generateLinksWithEntities = true)

  private var generateLinksTask: GenerateLinks = _

  override def run(context: ActivityContext[Seq[Link]]): Unit = {
    val entityDesc = DPair(linkSpec.entityDescriptions.source.copy(paths = paths.source.toIndexedSeq),
                           linkSpec.entityDescriptions.target.copy(paths = paths.target.toIndexedSeq))
    val op = new SampleOperator()
    val linkSpec2 = linkSpec.copy(rule = LinkageRule(op))

    generateLinksTask =
      new GenerateLinks(inputs, linkSpec2, runtimeConfig) {
        override def entityDescs = entityDesc
      }

    val listener = (v: Seq[Link]) =>  {
      if(v.size > 1000) generateLinksTask.cancelExecution()
    }
    context.status.update(0.0)
    context.executeBlocking(generateLinksTask, 0.8, listener)

    val generatedLinks = op.getLinks()
    assert(!generatedLinks.isEmpty, "Could not load any links")

    val shuffledLinks = for((s, t) <- generatedLinks zip (generatedLinks.tail :+ generatedLinks.head)) yield new Link(s.source, t.target, None, Some(DPair(s.entities.get.source, t.entities.get.target)))

    context.value.update(generatedLinks ++ shuffledLinks)
  }

  private class SampleOperator() extends SimilarityOperator {

    val links = Array.fill(paths.source.size, paths.target.size)(Seq[Link]())

    def getLinks() = {
      val a = links.flatten.flatten
      val c = a.groupBy(_.source).values.map(randomElement(_))
               .groupBy(_.target).values.map(randomElement(_))
      Random.shuffle(c.toSeq).take(1000)
    }

    val metric = EqualityMetric()

    val maxDistance = 0.0

    /** Maximum number of indices per property. If a property has more indices the remaining indices are ignored. */
    val maxIndices = 5

    def apply(entities: DPair[Entity], limit: Double = 0.0): Option[Double] = {
      for((sourcePath, sourceIndex) <- paths.source.zipWithIndex;
          (targetPath, targetIndex) <- paths.target.zipWithIndex) {
        val sourceValues = entities.source.evaluate(sourcePath)
        val targetValues = entities.target.evaluate(targetPath)
        val size = links(sourceIndex)(targetIndex).size
        val labelLinks = links(0)(0).size

        if(size <= 1000 && metric(sourceValues, targetValues, maxDistance) <= maxDistance) {
          links(sourceIndex)(targetIndex) :+= new Link(source = entities.source.uri, target = entities.target.uri, entities = Some(entities))
        }

        if (size > 1000 && labelLinks > 100)
          generateLinksTask.cancelExecution()
      }

      None
    }

    val id = Identifier.random

    val required = false

    val weight = 1

    val indexing = true

    private val inputs = (paths.source.toSet ++ paths.target.toSet).map(p => PathInput(path = p))

    def index(entity: Entity, limit: Double): Index = {
      val entities = DPair.fill(entity)

      val index = inputs.map(i => i(entities)).map(metric.index(_, maxDistance).crop(maxIndices)).reduce(_ merge _)

      index
    }

    def toXML(implicit prefixes: Prefixes): Node = throw new UnsupportedOperationException("Cannot serialize " + getClass.getName)
  }
}